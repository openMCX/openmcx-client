//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The remote change of a user's selected group, one implementation for MCPTT (TS 24.379 clause 10.1.4.2) and MCVideo
// (TS 24.281 clause 9.2.4.2): the procedures are the same in both specifications, with the service's ICSI, info body
// and PSI. The requesting side (clause 10.1.4.2.1 / 9.2.4.2.1) sends a SIP MESSAGE with <request-type>
// "group-selection-change-request" and reads the response's <selected-group-change-outcome>; the target side (clause
// 10.1.4.2.2 / 9.2.4.2.2) affiliates when asked, changes its selected group and answers. The state is the service's
// ServiceProcedures; what differs is how a service affiliates (selection_affiliate) and changes its selected group
// (selection_apply). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.484 V19.6.0 (Rel-19), RFC 3428,
// RFC 5366.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.1 step 2 a) and b) (and step 3 a) and b) of clause 10.1.4.2.2; TS 24.281 clause 9.2.4.2):
// the service's ICSI in P-Preferred-Service, and Accept-Contact with g.3gpp.icsi-ref (and the service's feature tag)
// "require" and "explicit".
std::vector<SipHeader> Client::service_message_headers(const ServiceProfile &service) const {
  return {{"P-Preferred-Service", service.icsi},
          {"Accept-Contact", std::string("*;+g.3gpp.icsi-ref=\"") + service.icsi_encoded + "\";require;explicit"},
          {"Accept-Contact", std::string("*;+") + service.feature_tag + ";require;explicit"}};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.1 (TS 24.281 clause 9.2.4.2.1): "remote-select <user> <group>" ("mcvideo-select-remote"
// for MCVideo):
//   1) MCPTT: with the user profile known and enforced (mcptt.enforce_profile_ruleset), a targeted user missing from
//      <RemoteGroupSelectionURIList> (TS 24.484), or no such list, is not authorised: the command fails (the
//      indication to the user) and nothing is sent;
//   1A) MCPTT: a group whose document carries <preconfigured-group-use-only> "true": "calls are not allowed", nothing
//      is sent (a group document is served to members only; otherwise the server decides, 167);
//   2) a SIP MESSAGE with the service's info body, <request-uri> the group and <request-type>
//      "group-selection-change-request", and the targeted user in a resource-lists body (RFC 5366);
//   3), 4) to the PSI of the participating function. A 4xx-6xx answer is the failure of the request (its status and
//      warning in the result).
// Deviation for MCVideo: steps 1 and 1A are left to the server, which refuses with 403 and warning 155 or 167 (TS
// 24.281 clause 9.2.4.3.1 step 3a, 9.2.4.4 step 2A); the lab checks those refusals (mcvideo_group_selection
// MV-SG-04, MV-SG-06), which a client-side check would hide.
void Client::select_remote(const ServiceProfile &service, const std::string &target, const std::string &group) {
  if (!service_authorized(service))
    throw std::runtime_error(std::string("group selection change requires ") + service.label + " service authorisation");
  auto &procs = procedures(service);
  if (service.service == Service::mcptt) {
    if (cfg_.at("mcptt").value("enforce_profile_ruleset", true) && profile_loaded_) {
      const auto allowed = remote_selection_targets_;
      if (std::find(allowed.begin(), allowed.end(), target) == allowed.end())
        throw std::runtime_error("not authorised to change the selected group of " + target);
    }
    auto document = http_.request("GET", groups_xcap() + "/global/byGroupID/" + xcap_segment(group), {},
                                  {{"Authorization", bearer()}});
    if (document.status == 200 && group_preconfigured_use_only(document.body))
      throw std::runtime_error("calls are not allowed on the preconfigured group");
  }
  McpttInfo info;
  info.request_uri = group;
  info.client_id = client_id();
  info.service["request-type"] = "group-selection-change-request";
  const std::vector<BodyPart> parts{{service.info_mime, service_info_xml(info, service)},
                                    {resource_lists_mime, private_call_resource_list(target)}};
  const auto boundary = "mcx-" + random_id();
  const auto request = ++procs.selection_requests;
  procs.selection_result = {{"target", target}, {"group", group}, {"status", 0},
                            {"warning", ""},    {"outcome", ""},  {"request", request}};
  sip_.request("MESSAGE", service_psi(service), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), service_message_headers(service),
               [this, &service, request](const SipResponse &response) {
                 auto &result = procedures(service).selection_result;
                 if (response.status_code < 200 || !result.is_object() || result.value("request", 0u) != request)
                   return;
                 result["status"] = response.status_code;
                 result["warning"] = std::string(get_header(response.headers, "Warning"));
                 // Clause 10.1.4.2.1: a 4xx, 5xx or 6xx response is the failure of the request.
                 if (response.status_code >= 300) {
                   result["outcome"] = "fail";
                   event(service_key(service, "group_selection_outcome"), result);
                 }
               });
  event(service_key(service, "group_selection_requested"), {{"target", target}, {"group", group}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.1, last paragraph (TS 24.281 clause 9.2.4.2.1): the "SIP MESSAGE request for group
// selection change response for terminating client": 1) the outcome from <selected-group-change-outcome>, 2) shown to
// the user (the result and the event).
void Client::selection_outcome(const ServiceProfile &service, const McpttInfo &info) {
  auto &result = procedures(service).selection_result;
  if (!result.is_object()) result = Json::object();
  result["outcome"] =
      info.service.contains("selected-group-change-outcome") ? info.service.at("selected-group-change-outcome") : "";
  if (!info.calling_group.empty()) result["group"] = info.calling_group;
  result["from"] = info.calling_user;
  event(service_key(service, "group_selection_outcome"), result);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.2 (TS 24.281 clause 9.2.4.2.2): the target client handles a group selection change
// request: 1) with <affiliation-required> "true", affiliate to the group in <mcptt-calling-group-id> first (clause
// 9.2.1.2) and await the NOTIFY with the PUBLISH's <p-id> (clause 9.2.1.3); 2) then (or with no affiliation required)
// change the selected group, and 3) answer.
void Client::selection_requested(const ServiceProfile &service, const McpttInfo &info) {
  auto &procs = procedures(service);
  const auto group = info.calling_group;
  const bool affiliate =
      info.service.contains("affiliation-required") && info.service.at("affiliation-required") == "true";
  procs.selection = {
      {"from", info.calling_user}, {"group", group}, {"affiliation_required", affiliate}, {"outcome", ""}};
  procs.selection_pending = nullptr;
  event(service_key(service, "group_selection_received"), procs.selection);
  if (group.empty() || info.calling_user.empty()) return selection_answer(service, false);
  if (!affiliate) return selection_affiliated(service, true);
  try {
    if (!selection_affiliate(service, group)) selection_affiliated(service, true);
  } catch (const std::exception &) {
    selection_affiliated(service, false);
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.2 step 1 a) (TS 24.281 clause 9.2.4.2.2): affiliate to the group by the procedure of
// clause 9.2.1.2 (8.2.1.2), remembering the PUBLISH's <p-id> for step 1 c). False when the client is affiliated to
// the group already (nothing to await); throws when the affiliation cannot be asked for.
bool Client::selection_affiliate(const ServiceProfile &service, const std::string &group) {
  auto &procs = procedures(service);
  if (service.service == Service::mcvideo) {
    mcvideo_affiliate(true, group);
    procs.selection_pending = {{"group", group}, {"p_id", mcvideo_sent_pid_}};
    return true;
  }
  if (affiliation_statuses_.contains(group) &&
      client_rules::counts_as_affiliated(affiliation_statuses_.value(group, std::string{})))
    return false;
  mcptt_sent_pid_.clear();
  if (!mcptt_change_affiliation({group}, {})) return false;
  procs.selection_pending = {{"group", group}, {"p_id", mcptt_sent_pid_}};
  return true;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.2 step 1 c) (TS 24.281 clause 9.2.4.2.2): the affiliation status NOTIFY with the <p-id>
// of the PUBLISH sent for the request tells whether the affiliation succeeded. A refused PUBLISH (an empty p-id
// here) is a failed affiliation.
void Client::selection_affiliation_notified(const ServiceProfile &service, const std::string &publication_id,
                                            bool group_affiliated) {
  const auto &pending = procedures(service).selection_pending;
  if (!pending.is_object()) return;
  if (!publication_id.empty() && publication_id != pending.value("p_id", std::string{})) return;
  schedule([this, &service, group_affiliated] { selection_affiliated(service, group_affiliated); });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.2 steps 1 c) and 2 (TS 24.281 clause 9.2.4.2.2): with the outcome of the affiliation (or
// none required), change the selected group (a)) and determine the success of the change (b)), then answer.
void Client::selection_affiliated(const ServiceProfile &service, bool affiliated) {
  auto &procs = procedures(service);
  procs.selection_pending = nullptr;
  if (!procs.selection.is_object() || !procs.selection.value("outcome", std::string{}).empty()) return;
  const bool changed = affiliated && selection_apply(service, procs.selection.at("group").get<std::string>());
  selection_answer(service, changed);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.2 step 2 a) and b) (TS 24.281 clause 9.2.4.2.2): make the group the selected one; false
// when this client cannot. MCPTT: as the user's own selection ("group <group>", which reads the group document and
// needs the client out of a call); MCVideo: the group selected before stays affiliated, as one of the extra groups.
bool Client::selection_apply(const ServiceProfile &service, const std::string &group) {
  if (service.service == Service::mcptt) {
    try {
      if (group != this->group()) select_group("group " + group);
    } catch (const std::exception &error) {
      failure(std::string("remote group selection: ") + error.what());
      return false;
    }
    return true;
  }
  const auto previous = mcvideo_group();
  mcvideo_selected_group_ = group;
  if (previous != mcvideo_selected_group_) mcvideo_extra_groups_.insert(previous);
  mcvideo_extra_groups_.erase(mcvideo_selected_group_);
  mcvideo_affiliated_ = mcvideo_groups_.contains(mcvideo_selected_group_);
  return true;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.4.2.2 steps 3 to 6 (TS 24.281 clause 9.2.4.2.2): a SIP MESSAGE with <response-type>
// "group-selection-change-response" and the <selected-group-change-outcome>, the group in <mcptt-request-uri>
// (<mcvideo-request-uri>) and the requester (<mcptt-calling-user-id> of the request) in a resource-lists body, to the
// PSI of the participating function; 4) the outcome shown to the user (status and event).
void Client::selection_answer(const ServiceProfile &service, bool success) {
  auto &procs = procedures(service);
  procs.selection["outcome"] = success ? "success" : "fail";
  McpttInfo info;
  info.request_uri = procs.selection.value("group", std::string{});
  info.client_id = client_id();
  info.service["response-type"] = "group-selection-change-response";
  info.service["selected-group-change-outcome"] = success ? "success" : "fail";
  const auto requester = procs.selection.value("from", std::string{});
  event(service_key(service, "group_selection_changed"), procs.selection);
  if (requester.empty() || info.request_uri.empty()) return;
  const std::vector<BodyPart> parts{{service.info_mime, service_info_xml(info, service)},
                                    {resource_lists_mime, private_call_resource_list(requester)}};
  const auto boundary = "mcx-" + random_id();
  sip_.request("MESSAGE", service_psi(service), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), service_message_headers(service), [this](const SipResponse &response) {
                 if (response.status_code >= 300)
                   failure("group selection change response: " + std::to_string(response.status_code));
               });
}

} // namespace mcx
