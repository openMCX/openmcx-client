//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo remote change of a user's selected group (TS 24.281 clause 9.2.4) and functional alias to group binding
// (TS 24.281 clause 20.4). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), TS 24.484 V19.6.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.4.2.1 step 2: the MCVideo client sends a SIP MESSAGE with <request-type>
// "group-selection-change-request", the group in <mcvideo-request-uri> and the targeted user in a resource-lists
// body. Deviation: the checks of step 1 (<RemoteGroupSelectionURIList> of the MCVideo user profile, TS 24.484) and
// step 1A (<preconfigured-group-use-only> of the group document) are left to the server, which refuses with 403
// and warning 155 (clause 9.2.4.3.1 step 3a) or 167 (clause 9.2.4.4 step 2A); the lab checks those refusals
// (mcvideo_group_selection MV-SG-04, MV-SG-06), which a client-side check would hide.
void Client::mcvideo_select_remote(const std::string &target, const std::string &group) {
  if (!mcvideo_authorized_) throw std::runtime_error("group selection change requires MCVideo service authorisation");
  McpttInfo info;
  info.request_uri = group;
  info.client_id = client_id();
  info.service["request-type"] = "group-selection-change-request";
  const std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                                    {resource_lists_mime, private_call_resource_list(target)}};
  const auto boundary = "mcx-" + random_id();
  mcvideo_selection_result_ = {{"target", target}, {"group", group}, {"status", 0},
                               {"warning", ""},    {"outcome", ""},  {"request", ++mcvideo_selection_requests_}};
  sip_.request("MESSAGE", mcvideo_service_uri(), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), mcvideo_call_headers(), [this](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_selection_result_["status"] = response.status_code;
                 mcvideo_selection_result_["warning"] = std::string(get_header(response.headers, "Warning"));
               });
  event("mcvideo_group_selection_requested", {{"target", target}, {"group", group}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.4.2.2: the target MCVideo client handles a group selection change request: affiliate first
// if <affiliation-required> is true (step 1), then select the group in <mcvideo-calling-group-id> (step 2) and
// answer (step 3).
void Client::mcvideo_selection_requested(const McpttInfo &info) {
  const auto group = info.calling_group;
  const bool affiliate =
      info.service.contains("affiliation-required") && info.service.at("affiliation-required") == "true";
  mcvideo_selection_ = {
      {"from", info.calling_user}, {"group", group}, {"affiliation_required", affiliate}, {"outcome", ""}};
  if (group.empty() || info.calling_user.empty()) return mcvideo_selection_answer(false);
  if (!affiliate) return mcvideo_selection_affiliated(true);
  try {
    mcvideo_affiliate(true, group);
    mcvideo_selection_pending_ = {{"group", group}, {"p_id", mcvideo_sent_pid_}};
  } catch (const std::exception &) {
    mcvideo_selection_affiliated(false);
  }
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.4.2.2 steps 1 c) and 2: with the outcome of the affiliation (or none required), change the
// selected group and answer.
void Client::mcvideo_selection_affiliated(bool affiliated) {
  mcvideo_selection_pending_ = nullptr;
  if (!mcvideo_selection_.is_object() || !mcvideo_selection_.value("outcome", std::string{}).empty()) return;
  if (affiliated) {
    // The group selected before stays affiliated, as one of the extra groups.
    const auto previous = mcvideo_group();
    mcvideo_selected_group_ = mcvideo_selection_.at("group").get<std::string>();
    if (previous != mcvideo_selected_group_) mcvideo_extra_groups_.insert(previous);
    mcvideo_extra_groups_.erase(mcvideo_selected_group_);
    mcvideo_affiliated_ = mcvideo_groups_.contains(mcvideo_selected_group_);
  }
  mcvideo_selection_answer(affiliated);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.4.2.2 steps 3 to 6: send a SIP MESSAGE with <response-type>
// "group-selection-change-response" and the <selected-group-change-outcome>, the group in <mcvideo-request-uri> and
// the requester in a resource-lists body.
void Client::mcvideo_selection_answer(bool success) {
  mcvideo_selection_["outcome"] = success ? "success" : "fail";
  McpttInfo info;
  info.request_uri = mcvideo_selection_.value("group", std::string{});
  info.client_id = client_id();
  info.service["response-type"] = "group-selection-change-response";
  info.service["selected-group-change-outcome"] = success ? "success" : "fail";
  const auto requester = mcvideo_selection_.value("from", std::string{});
  event("mcvideo_group_selection_changed", mcvideo_selection_);
  if (requester.empty() || info.request_uri.empty()) return;
  const std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                                    {resource_lists_mime, private_call_resource_list(requester)}};
  const auto boundary = "mcx-" + random_id();
  sip_.request("MESSAGE", mcvideo_service_uri(), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), mcvideo_call_headers(), [this](const SipResponse &response) {
                 if (response.status_code >= 300)
                   failure("group selection change response: " + std::to_string(response.status_code));
               });
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 20.4.2.1.2 (binding) and 20.4.2.1.3 (unbinding): the MCVideo client sends a SIP MESSAGE with
// <request-type> "fa-group-binding-req", the <binding-ind> and the functional alias, and the groups in a
// resource-lists body.
void Client::mcvideo_alias_binding(bool bind, const std::string &alias, const std::vector<std::string> &groups) {
  if (!mcvideo_authorized_) throw std::runtime_error("functional alias binding requires MCVideo service authorisation");
  if (alias.empty() || groups.empty()) throw std::runtime_error("mcvideo-fa-bind|unbind <alias> <group>[,<group>...]");
  if (bind && !mcvideo_aliases_.contains(alias))
    throw std::runtime_error("a functional alias must be activated to be bound");
  McpttInfo info;
  info.client_id = client_id();
  info.functional_alias = mcvideo_presented_alias();
  info.service["request-type"] = "fa-group-binding-req";
  info.service["binding-ind"] = bind ? "true" : "false";
  info.service[bind ? "binding-fa-uri" : "unbinding-fa-uri"] = alias;
  const std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                                    {resource_lists_mime, resource_list_xml(groups)}};
  const auto boundary = "mcx-" + random_id();
  const auto action = bind ? "bind" : "unbind";
  mcvideo_binding_result_ = {{"action", action}, {"alias", alias}, {"groups", groups},
                             {"status", 0},      {"warning", ""},  {"request", ++mcvideo_binding_requests_}};
  sip_.request("MESSAGE", mcvideo_service_uri(), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), mcvideo_call_headers(), [this](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_binding_result_["status"] = response.status_code;
                 mcvideo_binding_result_["warning"] = std::string(get_header(response.headers, "Warning"));
                 event("mcvideo_functional_alias_binding_result", mcvideo_binding_result_);
               });
  event("mcvideo_functional_alias_binding_requested", {{"action", action}, {"alias", alias}, {"groups", groups}});
}

} // namespace mcx
