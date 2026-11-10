//
// OpenMCX Licence Information : AGPL-3.0-or-later
//
// Affiliation of other MCPTT users and the MCPTT service settings subscription (member functions of Client):
// the affiliation status determination of a target user (TS 24.379 clause 9.2.1.3), the affiliation status change
// in mandatory mode of a target user (clause 9.2.1.2), the affiliation status change request in negotiated mode, sent
// (clause 9.2.1.4) and received (clause 9.2.1.5), and the determination of the MCPTT service settings (clause 7.2.4).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), RFC 3856, RFC 3903, RFC 4354, RFC 6665.
#include "../core/client.hpp"

#include <sstream>

namespace mcx {

namespace {
constexpr const char *affiliation_command_mime = "application/vnd.3gpp.mcptt-affiliation-command+xml";
constexpr unsigned max_expires = 4294967295u;

//------------------------------------------------------------------------------
// TS 24.379 annex F.4: the <command-list> of an affiliation status change request in negotiated mode.
std::string affiliation_command_xml(const std::vector<std::string> &affiliate, const std::vector<std::string> &leave) {
  std::string out =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?><command-list xmlns=\"urn:3gpp:ns:affiliationCommand:1.0\">";
  const auto list = [&out](const char *name, const std::vector<std::string> &groups) {
    if (groups.empty()) return;
    out += std::string("<") + name + ">";
    for (const auto &group : groups) out += "<group>" + xml_escape(group) + "</group>";
    out += std::string("</") + name + ">";
  };
  list("affiliate", affiliate);
  list("de-affiliate", leave);
  return out + "</command-list>";
}

//------------------------------------------------------------------------------
// TS 24.379 annex F.4: the groups under <affiliate> or <de-affiliate> of a <command-list>.
std::vector<std::string> command_groups(const std::string &xml, const std::string &list) {
  std::vector<std::string> out;
  const auto open = xml.find("<" + list + ">");
  if (open == std::string::npos) return out;
  const auto close = xml.find("</" + list + ">", open);
  for (auto at = xml.find("<group>", open); at != std::string::npos && at < close; at = xml.find("<group>", at + 7)) {
    const auto end = xml.find("</group>", at);
    if (end == std::string::npos) break;
    out.push_back(client_rules::xml_unescape(xml.substr(at + 7, end - at - 7)));
  }
  return out;
}
//------------------------------------------------------------------------------
// RFC 5621: the body of the given type, the whole body or one part of a multipart/mixed body; null when absent.
const std::string *body_of(const SipMessage &message, std::string_view mime) {
  if (message.content_type.starts_with(mime)) return &message.body;
  for (const auto &part : message.parts)
    if (part.content_type == mime) return &part.body;
  return nullptr;
}
} // namespace

//------------------------------------------------------------------------------
// Helper: a multipart/mixed body with the mcptt-info naming `target` in <mcptt-request-uri> and the other parts.
std::pair<std::string, std::string> Client::target_body(const std::string &target, std::vector<BodyPart> extra) {
  McpttInfo info;
  info.request_uri = target;
  auto parts = info_parts(info);
  for (auto &part : extra) parts.push_back(std::move(part));
  const auto boundary = "mcx-" + random_id();
  return {"multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary)};
}

//------------------------------------------------------------------------------
// The test commands of these procedures:
//   affiliation-subscribe <MCPTT ID>          clause 9.2.1.3: SUBSCRIBE (presence) for a target user's affiliations
//   affiliation-unsubscribe <MCPTT ID>        clause 9.2.1.3: the same subscription ended (Expires 0)
//   affiliate-user <MCPTT ID> <client ID> <group>...   clause 9.2.1.2, mandatory mode: PUBLISH for the target
//   deaffiliate-user <MCPTT ID> <client ID>   clause 9.2.1.2, mandatory mode: the target no longer interested
//   recommend-affiliation <MCPTT ID> affiliate|deaffiliate <group>...   clause 9.2.1.4: MESSAGE, negotiated mode
//   affiliation-request accept|reject         clause 9.2.1.5: the user's answer to a received request
//   settings-subscribe | settings-resubscribe | settings-unsubscribe   clause 7.2.4: the MCPTT service settings
void Client::remote_affiliation_command(const std::string &cmd) {
  std::istringstream in(cmd);
  std::string verb;
  in >> verb;
  std::vector<std::string> args;
  for (std::string word; in >> word;) args.push_back(word);
  if (!authorized_) throw std::runtime_error("authorization required");
  if (verb == "affiliation-subscribe" && args.size() == 1) return subscribe_target_affiliation(args[0]);
  if (verb == "affiliation-unsubscribe" && args.size() == 1) return unsubscribe_target_affiliation(args[0]);
  if (verb == "affiliate-user" && args.size() >= 3)
    return publish_target_affiliation(args[0], args[1], {args.begin() + 2, args.end()});
  if (verb == "deaffiliate-user" && args.size() == 2) return publish_target_affiliation(args[0], args[1], {});
  if (verb == "recommend-affiliation" && args.size() >= 3 && (args[1] == "affiliate" || args[1] == "deaffiliate")) {
    const std::vector<std::string> groups{args.begin() + 2, args.end()};
    return recommend_affiliation(args[0], args[1] == "affiliate" ? groups : std::vector<std::string>{},
                                 args[1] == "deaffiliate" ? groups : std::vector<std::string>{});
  }
  if (verb == "affiliation-request" && args.size() == 1 && (args[0] == "accept" || args[0] == "reject"))
    return answer_affiliation_request(args[0] == "accept");
  if (verb == "settings-subscribe" && args.empty()) return subscribe_settings(false);
  if (verb == "settings-resubscribe" && args.empty()) return subscribe_settings(true);
  if (verb == "settings-unsubscribe" && args.empty()) return unsubscribe_settings();
  throw std::runtime_error("affiliation-subscribe|affiliation-unsubscribe <MCPTT ID>, affiliate-user <MCPTT ID> "
                           "<client ID> <group>..., deaffiliate-user <MCPTT ID> <client ID>, recommend-affiliation "
                           "<MCPTT ID> affiliate|deaffiliate <group>..., affiliation-request accept|reject, "
                           "settings-subscribe|settings-resubscribe|settings-unsubscribe");
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.3 (item 2, "which another MCPTT user is affiliated to"): the initial SUBSCRIBE to the
// originating participating function, the mcptt-info naming the target user, the MCPTT ICSI, Expires 4294967295 and
// the Accept of application/pidf+xml; no simple-filter (item 7: only for this user's own client).
void Client::subscribe_target_affiliation(const std::string &target) {
  if (target == user()) throw std::runtime_error("affiliation-subscribe names another user");
  for (const auto &[dialog, id] : target_affiliation_subscriptions_)
    if (id == target) throw std::runtime_error("already subscribed to the affiliation status of " + target);
  const auto [type, body] = target_body(target);
  auto created = std::make_shared<std::string>();
  const auto dialog = sip_.subscribe(
      service(), "presence", max_expires, {{"P-Preferred-Service", mcptt_icsi}, {"Accept", "application/pidf+xml"}},
      [this, target, created](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["target_affiliations"][target]["subscription_status"] = response.status_code;
        if (response.status_code >= 300)
          schedule([this, created] { target_affiliation_subscriptions_.erase(*created); });
      },
      type, body);
  *created = dialog;
  target_affiliation_subscriptions_[dialog] = target;
  event("target_affiliation_subscribed", {{"target", target}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.3, de-subscribe: the in-dialog SUBSCRIBE with Expires 0 and the Accept of
// application/pidf+xml (the mcptt-info naming the target, TS 37.579-2 table 5.3.3.3-7).
void Client::unsubscribe_target_affiliation(const std::string &target) {
  const auto found = std::find_if(target_affiliation_subscriptions_.begin(), target_affiliation_subscriptions_.end(),
                                  [&target](const auto &entry) { return entry.second == target; });
  if (found == target_affiliation_subscriptions_.end())
    throw std::runtime_error("no subscription to the affiliation status of " + target);
  const auto dialog = found->first;
  const auto [type, body] = target_body(target);
  sip_.request_in_dialog(dialog, "SUBSCRIBE", type, body,
                         {{"Event", "presence"}, {"Expires", "0"}, {"Accept", "application/pidf+xml"},
                          {"P-Preferred-Service", mcptt_icsi}},
                         [this, target](const SipResponse &response) {
                           if (response.status_code >= 200)
                             status_["target_affiliations"][target]["unsubscribe_status"] = response.status_code;
                         });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.3: a NOTIFY of the subscription to a target user's affiliation status: the groups of the
// target's clients, shown to the user (status target_affiliations).
SipResponse Client::target_affiliation_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto found = target_affiliation_subscriptions_.find(message.dialog_id);
  if (message.from_uri != service() || asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  const auto target = found->second;
  const auto *pidf = body_of(message, "application/pidf+xml");
  auto &state = status_["target_affiliations"][target];
  if (pidf) {
    const auto affiliation = parse_affiliation(*pidf, {});
    if (affiliation.user_id != target) throw std::runtime_error("notification identity mismatch");
    Json groups = Json::object();
    for (std::size_t index = 0; index < affiliation.groups.size(); ++index)
      groups[affiliation.groups[index]] =
          index < affiliation.statuses.size() && !affiliation.statuses[index].empty() ? affiliation.statuses[index]
                                                                                     : "affiliated";
    state["groups"] = groups;
  }
  state["notifies"] = state.value("notifies", 0) + 1;
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    target_affiliation_subscriptions_.erase(found);
    state["subscribed"] = false;
  } else {
    state["subscribed"] = true;
  }
  event("target_affiliation_status", {{"target", target}, {"groups", state.value("groups", Json::object())}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.2, mandatory mode ("an affiliation status change request in mandatory mode to another
// MCPTT user"): the PUBLISH to the originating participating function, the mcptt-info naming the target user
// (clause 9.2.2.2.3 step 1: the served MCPTT ID), the MCPTT ICSI, the Accept of application/pidf+xml (TS 37.579-1
// table 5.5.2.11-1, condition PRESENCE-EVENT) and a PIDF for the target's client (clause 9.3.1): its groups with
// Expires 4294967295 (item 5), or, the target no longer interested in any group, an empty <status> with Expires 0
// (item 4; NOTE 2 lets the PIDF be left out, TS 37.579-2 table 5.3.3.3-20 has it empty).
void Client::publish_target_affiliation(const std::string &target, const std::string &client,
                                        const std::vector<std::string> &groups) {
  if (target == user()) throw std::runtime_error("affiliate-user names another user");
  Affiliation request{target, client, random_id(), groups, {}};
  const auto [type, body] = target_body(target, {{"application/pidf+xml", affiliation_xml(request, false)}});
  sip_.publish(service(), type, body,
               {{"P-Preferred-Service", mcptt_icsi},
                {"Event", "presence"},
                {"Expires", groups.empty() ? "0" : "4294967295"},
                {"Accept", "application/pidf+xml"}},
               [this, target, removing = groups.empty()](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 status_["target_affiliation_changes"][target] = {{"status", response.status_code},
                                                                  {"removing", removing}};
                 if (response.status_code != 200)
                   failure("mandatory mode affiliation change: " + std::to_string(response.status_code));
               });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.4: the affiliation status change request in negotiated mode: a SIP MESSAGE to the
// originating participating function, the mcptt-info naming the target user, the MCPTT ICSI and the
// application/vnd.3gpp.mcptt-affiliation-command+xml (annex F.4); a 2xx is shown as delivered.
void Client::recommend_affiliation(const std::string &target, const std::vector<std::string> &affiliate,
                                   const std::vector<std::string> &leave) {
  if (target == user()) throw std::runtime_error("recommend-affiliation names another user");
  const auto [type, body] =
      target_body(target, {{affiliation_command_mime, affiliation_command_xml(affiliate, leave)}});
  // TS 37.579-1 table 5.5.2.7.1-1: the Accept-Contact of the MCPTT ICSI (as the other MCPTT service MESSAGEs).
  sip_.request("MESSAGE", service(), type, body,
               {{"P-Preferred-Service", mcptt_icsi},
                {"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"},
                {"Accept-Contact",
                 "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"}},
               [this, target](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 status_["affiliation_recommendations"][target] = {{"status", response.status_code},
                                                                   {"delivered", response.status_code < 300}};
               });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.5 steps 1 and 2: a received request in negotiated mode is answered 200 (OK) and shown to
// the user for confirmation (status affiliation_request: the requesting user and the groups).
SipResponse Client::affiliation_command_message(const SipMessage &message, const std::string &command) {
  SipResponse response;
  response.status_code = 200;
  McpttInfo info;
  try {
    info = message_info(message);
  } catch (const std::exception &) {
  }
  affiliation_request_ = {{"from", info.calling_user},
                          {"affiliate", command_groups(command, "affiliate")},
                          {"deaffiliate", command_groups(command, "de-affiliate")},
                          {"answered", false}};
  status_["affiliation_request"] = affiliation_request_;
  event("affiliation_request", affiliation_request_);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.5 step 3: the user accepts (or rejects) the request: affiliation to each group of
// <affiliate> this client is not affiliated to, de-affiliation from each group of <de-affiliate> it is affiliated
// to, by the affiliation status change procedure (clause 9.2.1.2). The selected group is affiliated as by
// "affiliate"; another group is monitored besides it.
void Client::answer_affiliation_request(bool accept) {
  if (affiliation_request_.is_null() || affiliation_request_.value("answered", true))
    throw std::runtime_error("no affiliation request to answer");
  affiliation_request_["answered"] = true;
  affiliation_request_["accepted"] = accept;
  status_["affiliation_request"] = affiliation_request_;
  if (!accept) return;
  bool publish = false, leave_selected = false;
  for (const auto &group : affiliation_request_.at("affiliate").get<std::vector<std::string>>()) {
    if (group == this->group()) {
      desired_affiliation_ = true;
    } else if (std::find(monitored_groups_.begin(), monitored_groups_.end(), group) == monitored_groups_.end()) {
      monitored_groups_.push_back(group);
    }
    publish = true;
  }
  for (const auto &group : affiliation_request_.at("deaffiliate").get<std::vector<std::string>>()) {
    if (group == this->group()) {
      leave_selected = true;
    } else if (const auto at = std::find(monitored_groups_.begin(), monitored_groups_.end(), group);
               at != monitored_groups_.end()) {
      monitored_groups_.erase(at);
      publish = true;
    }
  }
  if (leave_selected) return deaffiliate_command();
  if (publish) affiliate();
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.4: the SUBSCRIBE (Event poc-settings) to the originating participating function, the
// mcptt-info naming this MCPTT user, the MCPTT ICSI, the Accept of application/poc-settings+xml and Expires
// 4294967295 (item 6, current status and later notifications); `again`: the re-subscription in its dialog.
void Client::subscribe_settings(bool again) {
  const auto [type, body] = target_body(user());
  const std::vector<SipHeader> headers{{"P-Preferred-Service", mcptt_icsi},
                                       {"Accept", "application/poc-settings+xml"}};
  if (again) {
    if (settings_subscription_.empty()) throw std::runtime_error("no MCPTT service settings subscription");
    auto in_dialog = headers;
    in_dialog.push_back({"Event", "poc-settings"});
    in_dialog.push_back({"Expires", "4294967295"});
    sip_.request_in_dialog(settings_subscription_, "SUBSCRIBE", type, body, in_dialog,
                           [this](const SipResponse &response) {
                             if (response.status_code >= 200)
                               status_["service_settings"]["resubscribe_status"] = response.status_code;
                           });
    return;
  }
  if (!settings_subscription_.empty()) throw std::runtime_error("already subscribed to the MCPTT service settings");
  settings_subscription_ = sip_.subscribe(service(), "poc-settings", max_expires, headers,
                                          [this](const SipResponse &response) {
                                            if (response.status_code < 200) return;
                                            status_["service_settings"]["subscription_status"] = response.status_code;
                                            if (response.status_code >= 300)
                                              schedule([this] { settings_subscription_.clear(); });
                                          },
                                          type, body);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.4: de-subscribe: the in-dialog SUBSCRIBE with Expires 0.
void Client::unsubscribe_settings() {
  if (settings_subscription_.empty()) throw std::runtime_error("no MCPTT service settings subscription");
  const auto [type, body] = target_body(user());
  sip_.request_in_dialog(settings_subscription_, "SUBSCRIBE", type, body,
                         {{"P-Preferred-Service", mcptt_icsi},
                          {"Event", "poc-settings"},
                          {"Expires", "0"},
                          {"Accept", "application/poc-settings+xml"}},
                         [this](const SipResponse &response) {
                           if (response.status_code >= 200)
                             status_["service_settings"]["unsubscribe_status"] = response.status_code;
                         });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.4 with RFC 4354: a NOTIFY of the MCPTT service settings subscription, answered 200 (OK); the
// settings are shown to the user (status service_settings).
SipResponse Client::settings_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (message.from_uri != service() || asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  auto &state = status_["service_settings"];
  state["notifies"] = state.value("notifies", 0) + 1;
  if (const auto *settings = body_of(message, "application/poc-settings+xml")) {
    const auto mode = settings->find("<answer-mode>");
    if (mode != std::string::npos)
      state["answer_mode"] = settings->substr(mode + 13, settings->find("</answer-mode>", mode) - mode - 13);
    try {
      state["user_profile_index"] = parse_profile_index(*settings);
    } catch (const std::exception &) {
    }
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    settings_subscription_.clear();
    state["subscribed"] = false;
  } else {
    state["subscribed"] = true;
  }
  return response;
}

} // namespace mcx
