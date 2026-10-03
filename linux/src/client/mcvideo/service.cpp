// MCVideo client: media security keys, service authorisation, affiliation, test commands and status
// (TS 24.281 clauses 4.7, 7.2, 8.2.1). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), TS 24.581 V19.2.0 (Rel-19), TS 24.481 V19.3.0 (Rel-19),
// TS 33.180 V19.4.0 (Rel-19), RFC 5875.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: the MCVideo selected group: the one a remote change set (TS 24.281 clause 9.2.4.2.2), else the configured
// one.
std::string Client::mcvideo_group() const {
  return mcvideo_selected_group_.empty() ? cfg_.at("mcvideo").at("group_id").get<std::string>()
                                         : mcvideo_selected_group_;
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 10.2.2.2.1 step 8 and 15.2.1.1 with TS 33.180 clause 7.4.1: a one-to-one call's media under a
// PCK the caller generates for the called MCVideo ID, offered in a=key-mgmt; its streams take the MCVideo private call
// CS-IDs 2 (initiator) and 3 (receiver) of TS 33.180 table E.1.3-1.
std::optional<keying::KeyMessage> Client::mcvideo_offer_pck(const std::string &target) {
  if (!protected_media()) return std::nullopt;
  auto &call = *mcvideo_call_;
  if (!mcvideo_keys_) throw std::runtime_error("MCVideo key management client unavailable");
  auto [key, message] = mcvideo_keys_->make_pck(target);
  call.local.protected_media = true;
  call.local.key_mgmt = std::move(message);
  return key;
}

//------------------------------------------------------------------------------
// TS 33.180 clause 7.4.1: the offer or answer is out: the PCK is selected for this call, and a re-INVITE does not
// carry it again.
void Client::mcvideo_bind_pck(const keying::KeyMessage &key, bool initiator) {
  auto &call = *mcvideo_call_;
  call.local.key_mgmt.clear();
  mcvideo_protection_.set_private(key, initiator);
  call.pck_id = key.csb_id;
  status_["mcvideo_private_key_id"] = key.csb_id;
  event("mcvideo_private_call_key", {{"pck_id", key.csb_id}, {"initiator", initiator}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 4.7.1 with TS 24.481 clause 6.3.13.2.1: the group's GMK through the subscription to the <GKTPs>
// element of the MCS GKTP document, sent to the MCVideo server.
void Client::mcvideo_subscribe_keys() {
  if (!protected_media() || !mcvideo_keys_) return;
  McpttInfo info;
  info.access_token = token_;
  info.client_id = client_id();
  info.request_uri = mcvideo_group();
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                              {"application/resource-lists+xml", keying::gktp_resource_list(mcvideo_group())}};
  const auto boundary = "mcx-" + random_id();
  mcvideo_key_subscription_ = sip_.subscribe(
      mcvideo_service_uri(), "xcap-diff", 60, {{"P-Preferred-Service", mcvideo_service.icsi}},
      [this](const SipResponse &response) {
        if (response.status_code >= 300) {
          mcvideo_key_subscription_.clear();
          failure("MCVideo group key subscription: " + std::to_string(response.status_code));
        }
      },
      "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
  mcvideo_key_group_ = mcvideo_group();
  mcvideo_next_key_refresh_ = monotonic_ms() + 50000;
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: the NOTIFY (RFC 5875 xcap-diff) of the GKTP subscription: open the GMK it carries
// (TS 33.180) and protect the group's media with it.
SipResponse Client::mcvideo_key_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != mcvideo_service_uri() || message.from_uri != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    mcvideo_protection_.clear_group();
    mcvideo_key_subscription_.clear();
    return response;
  }
  if (!mcvideo_keys_ || message.content_type != "application/xcap-diff+xml") {
    response.status_code = 400;
    return response;
  }
  const auto document = keying::parse_gktp_xcap_diff(message.body, mcvideo_key_group_, xcap_root());
  const auto key = mcvideo_keys_->open_group(gms_identity(), mcvideo_key_group_, document);
  mcvideo_protection_.set_group(key);
  status_["mcvideo_group_key_id"] = key.csb_id;
  event("mcvideo_group_key_installed", {{"group_id", mcvideo_key_group_}, {"key_id", key.csb_id}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 7.2.1A: the common SIP PUBLISH procedure: to the MCVideo PSI with the MCVideo ICSI in
// P-Preferred-Service.
void Client::mcvideo_request(const std::string &event_name, const std::vector<BodyPart> &parts,
                             std::vector<SipHeader> headers, std::function<void(const SipResponse &)> done) {
  const auto boundary = "mcvideo-" + random_id();
  headers.push_back({"P-Preferred-Service", mcvideo_service.icsi});
  headers.push_back({"Event", event_name});
  sip_.request("PUBLISH", mcvideo_service_uri(), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), headers, [done](const SipResponse &response) {
                 if (response.status_code >= 200) done(response);
               });
}

//------------------------------------------------------------------------------
// Helper: keep the outcome of the last MCVideo PUBLISH in mcvideo_last_result_.
void Client::mcvideo_result(const std::string &action, const SipResponse &response) {
  mcvideo_last_result_ = {{"action", action},
                          {"status", response.status_code},
                          {"warning", std::string(get_header(response.headers, "Warning"))}};
}

//------------------------------------------------------------------------------
// TS 24.281 clause 7.2.2: service authorisation and MCVideo service settings by PUBLISH; token replaces the access
// token (a test aid for the 101 refusal of TS 24.281 clause 7.3.3 step 6).
void Client::mcvideo_authorize(const std::string &token) {
  if (!mcvideo_enabled() || token_.empty()) return;
  McpttInfo info;
  info.access_token = token.empty() ? token_ : token;
  info.client_id = client_id();
  std::vector<SipHeader> headers{{"Expires", "4294967295"}};
  if (!mcvideo_auth_etag_.empty() && token.empty()) headers.push_back({"SIP-If-Match", mcvideo_auth_etag_});
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                              {"application/poc-settings+xml", poc_settings_xml(client_id())}};
  if (!mcvideo_csk_upload_.empty()) parts.push_back({"application/mikey", mcvideo_csk_upload_});
  mcvideo_request("poc-settings", parts, headers, [this, probe = !token.empty()](const SipResponse &response) {
    mcvideo_result("authorize", response);
    if (probe) return; // the real authorisation stays as it was
    if (response.status_code != 200) {
      mcvideo_authorized_ = false;
      failure("MCVideo service authorisation: " + std::to_string(response.status_code));
      return;
    }
    mcvideo_auth_etag_ = get_header(response.headers, "SIP-ETag");
    mcvideo_authorized_ = true;
    event("mcvideo_service_authorized");
    // TS 24.281 clause 18.3.4.1: the initial log-on trigger, once a configuration is held.
    mcvideo_location_logon_pending_ = !mcvideo_location_.configured();
    mcvideo_location_.signal(location::Event::InitialLogOn, monotonic_ms());
    // TS 24.281 clause 8.2.1.3: learn the affiliation status first; the affiliation
    // PUBLISH follows the first NOTIFY.
    if (mcvideo_subscription_.empty()) schedule([this] { mcvideo_subscribe(); });
    if (mcvideo_key_subscription_.empty())
      schedule([this] { mcvideo_subscribe_keys(); });
    else if (mcvideo_desired_affiliation_)
      schedule([this] { mcvideo_affiliate(true); });
  });
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.2.1.3: the affiliation status determination procedure: SUBSCRIBE to this user's affiliation
// status at the MCVideo server.
void Client::mcvideo_subscribe() {
  if (!mcvideo_authorized_ || !mcvideo_subscription_.empty()) return;
  mcvideo_status_known_ = false;
  mcvideo_subscription_ =
      sip_.subscribe(mcvideo_service_uri(), "presence", 3600,
                     {{"P-Preferred-Service", mcvideo_service.icsi}, {"Accept", "application/pidf+xml"}},
                     [this](const SipResponse &response) {
                       if (response.status_code >= 300) {
                         mcvideo_subscription_.clear();
                         failure("MCVideo affiliation subscription: " + std::to_string(response.status_code));
                       }
                     });
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.2.1.3: NOTIFY with the per-user affiliation information (TS 24.281 clause 8.3.1.2).
SipResponse Client::mcvideo_affiliation_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  if (get_header(message.headers, "Event") != "presence") {
    response.status_code = 489;
    return response;
  }
  const bool terminated = get_header(message.headers, "Subscription-State").starts_with("terminated");
  Affiliation affiliation;
  try {
    affiliation = parse_affiliation(message.parts.empty() ? message.body : message.parts.front().body);
  } catch (const std::exception &error) {
    event("mcvideo_affiliation_notify_refused", {{"reason", error.what()}});
    response.status_code = 400;
    return response;
  }
  // An empty status has no <affiliation> element to show its namespace.
  if (!affiliation.mcvideo && !affiliation.groups.empty()) {
    event("mcvideo_affiliation_notify_refused", {{"reason", "no MCVideo namespace"}});
    response.status_code = 400;
    return response;
  }
  if (affiliation.user_id != mcvideo_user() || affiliation.client_id != client_id()) {
    event("mcvideo_affiliation_notify_refused", {{"reason", "identity mismatch"}});
    response.status_code = 403;
    return response;
  }
  std::set<std::string> groups;
  for (std::size_t index = 0; index < affiliation.groups.size(); ++index)
    if (index < affiliation.statuses.size() && affiliation.statuses[index] == "affiliated")
      groups.insert(affiliation.groups[index]);
  mcvideo_groups_ = groups;
  mcvideo_affiliated_ = groups.contains(mcvideo_group());
  if (!affiliation.publication_id.empty()) mcvideo_received_pid_ = affiliation.publication_id;
  // TS 24.281 clause 9.2.4.2.2 step 1 c): the NOTIFY for the affiliation a group selection change asked for.
  if (mcvideo_selection_pending_.is_object() && !affiliation.publication_id.empty() &&
      affiliation.publication_id == mcvideo_selection_pending_.value("p_id", std::string{})) {
    const std::string group = mcvideo_selection_pending_.at("group");
    schedule([this, ok = groups.contains(group)] { mcvideo_selection_affiliated(ok); });
  }
  event("mcvideo_affiliation_status", {{"groups", groups}, {"terminated", terminated}});
  if (terminated) {
    mcvideo_subscription_.clear();
    mcvideo_status_known_ = false;
    return response;
  }
  if (!mcvideo_status_known_) {
    mcvideo_status_known_ = true;
    if (mcvideo_desired_affiliation_ && !mcvideo_affiliated_) schedule([this] { mcvideo_affiliate(true); });
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.2.1.2: affiliation status change by PUBLISH with the client's whole set of
// groups: the configured group and those added with a group argument, which
// joins the set once the server accepts it. De-affiliation (Expires 0) leaves all.
void Client::mcvideo_affiliate(bool affiliate, const std::string &group) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo affiliation requires MCVideo service authorisation");
  if (group.empty()) mcvideo_desired_affiliation_ = affiliate;
  if (!affiliate) mcvideo_extra_groups_.clear();
  McpttInfo info;
  info.request_uri = mcvideo_user();
  info.client_id = client_id();
  auto affiliation = Affiliation{mcvideo_user(), client_id(), random_id(), {mcvideo_group()}, {}};
  for (const auto &extra : mcvideo_extra_groups_)
    if (extra != mcvideo_group()) affiliation.groups.push_back(extra);
  if (!group.empty() &&
      std::find(affiliation.groups.begin(), affiliation.groups.end(), group) == affiliation.groups.end())
    affiliation.groups.push_back(group);
  affiliation.mcvideo = true;
  mcvideo_sent_pid_ = affiliation.publication_id;
  std::vector<SipHeader> headers{{"Expires", affiliate ? "4294967295" : "0"}};
  if (!mcvideo_affiliation_etag_.empty()) headers.push_back({"SIP-If-Match", mcvideo_affiliation_etag_});
  mcvideo_request("presence",
                  {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                   {"application/pidf+xml", affiliation_xml(affiliation, false)}},
                  headers, [this, affiliate, group](const SipResponse &response) {
                    mcvideo_result(affiliate ? "affiliate" : "deaffiliate", response);
                    if (response.status_code != 200) {
                      // A refused extra group leaves the affiliation as it was.
                      if (group.empty()) failure("MCVideo affiliation: " + std::to_string(response.status_code));
                      if (mcvideo_selection_pending_.is_object() &&
                          mcvideo_selection_pending_.value("group", std::string{}) == group)
                        schedule([this] { mcvideo_selection_affiliated(false); });
                      return;
                    }
                    if (!group.empty()) mcvideo_extra_groups_.insert(group);
                    mcvideo_affiliation_etag_ =
                        affiliate ? std::string(get_header(response.headers, "SIP-ETag")) : std::string{};
                    // The affiliation status itself comes from the NOTIFY (TS 24.281 clause 8.2.1.3).
                    event(affiliate ? "mcvideo_affiliation_requested" : "mcvideo_deaffiliation_requested");
                  });
}

//------------------------------------------------------------------------------
// Helper: the MCVideo test commands, each dispatched to the procedure it starts:
//   mcvideo-affiliate [group]   mcvideo-deaffiliate   mcvideo-authorize [token]
//   mcvideo-call [implicit]   mcvideo-chat <group>   mcvideo-private <MCVideo ID> [auto|manual|force-auto] [no-control]
//   mcvideo-answer   mcvideo-reject   mcvideo-transmit   mcvideo-release   mcvideo-end
//   mcvideo-call emergency|imminent   mcvideo-emergency on|off   mcvideo-imminent on|off   mcvideo-alert on|off
//   mcvideo-receive   mcvideo-stop-receiving   mcvideo-hangup
//   mcvideo-push <MCVideo ID> [auto|manual|force-auto]   mcvideo-pull <MCVideo ID> [auto|manual|force-auto]
//   mcvideo-remote-transmit <MCVideo ID>   mcvideo-remote-cancel <MCVideo ID>
//   mcvideo-push-server   mcvideo-pull-server <video URL>
//   mcvideo-ambient <MCVideo ID> remote|local
//   mcvideo-adhoc <MCVideo ID>...   mcvideo-adhoc criteria <criteria>
//   mcvideo-call group=<group>   mcvideo-regroup create <TGI> <preconfigured group> groups|users <URI>,...
//   mcvideo-regroup remove <TGI>
//   mcvideo-fa activate|takeover|deactivate <alias>...   mcvideo-fa use <alias>|none
//   mcvideo-private-fa <alias> [auto|manual|force-auto]
//   mcvideo-fa-subscribe [alias]   mcvideo-fa-fetch [alias]   mcvideo-fa-unsubscribe [alias]
//   mcvideo-fa-bind <alias> <group>[,<group>...]   mcvideo-fa-unbind <alias> <group>[,<group>...]
//   mcvideo-select-remote <MCVideo ID> <group>
//   mcvideo-adhoc-alert <criteria>   mcvideo-adhoc-alert cancel
//   mcvideo-location request [refresh] [filter <area> in|out] [fa <alias>] [no-list] [<MCVideo ID>...]
//   mcvideo-location configure <MCVideo ID>[,<MCVideo ID>...] <configuration JSON>
void Client::mcvideo_command(const std::string &cmd) {
  if (!mcvideo_enabled()) throw std::runtime_error("MCVideo is not configured");
  std::istringstream words(cmd);
  std::string verb, argument;
  words >> verb >> argument;
  if (verb == "mcvideo-affiliate") return mcvideo_affiliate(true, argument);
  if (verb == "mcvideo-deaffiliate") return mcvideo_affiliate(false);
  if (verb == "mcvideo-authorize") return mcvideo_authorize(argument);
  if (verb == "mcvideo-call") {
    if (argument == "emergency" || argument == "imminent") return mcvideo_group_call(false, {}, argument);
    if (argument.starts_with("group=")) return mcvideo_group_call(false, {}, {}, argument.substr(6));
    return mcvideo_group_call(argument == "implicit");
  }
  if (verb == "mcvideo-emergency" || verb == "mcvideo-imminent") {
    if (argument != "on" && argument != "off") throw std::runtime_error(verb + " on|off");
    return mcvideo_change_priority(verb == "mcvideo-emergency", argument == "on");
  }
  if (verb == "mcvideo-alert") {
    if (argument != "on" && argument != "off") throw std::runtime_error("mcvideo-alert on|off");
    return mcvideo_alert(argument == "on");
  }
  if (verb == "mcvideo-private") return mcvideo_private_command(argument, words);
  // TS 24.281 clause 20.2.1.2: functional alias status change, and the alias presented in calls.
  if (verb == "mcvideo-fa") {
    std::vector<std::string> aliases;
    for (std::string alias; words >> alias;) aliases.push_back(alias);
    return mcvideo_functional_alias(argument, aliases);
  }
  // TS 24.281 clause 20.2.1.3: status determination for this user's aliases, or for the users of an alias.
  if (verb == "mcvideo-fa-subscribe" || verb == "mcvideo-fa-fetch")
    return mcvideo_alias_subscribe(argument, verb == "mcvideo-fa-fetch");
  if (verb == "mcvideo-fa-unsubscribe") return mcvideo_alias_unsubscribe(argument);
  // TS 24.281 clauses 18.3.2.2 and 18.3.3.2: an authorised user's location request or configuration change.
  if (verb == "mcvideo-location") return mcvideo_location_command(cmd);
  // TS 24.281 clauses 12.1A.1.1 and 12.1A.1.2: an ad hoc group emergency alert, or its cancellation.
  if (verb == "mcvideo-adhoc-alert") return mcvideo_adhoc_alert_command(argument, words);
  // TS 24.281 clause 9.2.4.2.1: ask the MCVideo server to change another user's selected group.
  if (verb == "mcvideo-select-remote") {
    std::string group;
    words >> group;
    if (argument.empty() || group.empty()) throw std::runtime_error("mcvideo-select-remote <MCVideo ID> <group>");
    return mcvideo_select_remote(argument, group);
  }
  // TS 24.281 clauses 20.4.2.1.2 and 20.4.2.1.3: bind an alias to groups, or unbind it.
  if (verb == "mcvideo-fa-bind" || verb == "mcvideo-fa-unbind")
    return mcvideo_alias_binding_command(verb, argument, words);
  // TS 24.281 clause 10.2.2.2.1 step 11 c) ii) A): a private call to a functional alias.
  if (verb == "mcvideo-private-fa") {
    std::string mode;
    words >> mode;
    return mcvideo_private_call(argument, mode, false, false, "private", true);
  }
  // TS 24.281 clauses 13.2.2.1 and 12.2.2.1: a one-to-one video push or pull.
  if (verb == "mcvideo-push" || verb == "mcvideo-pull") {
    std::string mode;
    words >> mode;
    return mcvideo_private_call(argument, mode, false, false,
                                verb == "mcvideo-push" ? "one-to-one video push" : "one-to-one video pull");
  }
  // TS 24.281 clauses 21.2.1.1, 21.3.1.1 and 21.2.1.2: create a group or user regroup using a
  // preconfigured group, or remove one.
  if (verb == "mcvideo-regroup") return mcvideo_regroup_request_command(argument, words);
  // TS 24.281 clause 22.2.2.1.1: an ad hoc group call to MCVideo users or by criteria.
  if (verb == "mcvideo-adhoc") return mcvideo_adhoc_command(argument, words);
  // TS 24.281 clause 15.2.1.1: an ambient viewing call, viewing the target (remote) or
  // being viewed by it (local).
  if (verb == "mcvideo-ambient") {
    std::string kind;
    words >> kind;
    if (kind != "remote" && kind != "local") throw std::runtime_error("mcvideo-ambient <MCVideo ID> remote|local");
    return mcvideo_ambient_call(argument, kind + "-init");
  }
  // TS 24.281 clauses 13.2.2.4 and 12.2.2.4: push a video to, or pull one from, the server.
  if (verb == "mcvideo-push-server") return mcvideo_server_call("one-to-server video push");
  if (verb == "mcvideo-pull-server") {
    if (argument.empty()) throw std::runtime_error("mcvideo-pull-server needs the URL of the video");
    return mcvideo_server_call("one-from-server video pull", argument);
  }
  if (verb == "mcvideo-answer" || verb == "mcvideo-reject") return mcvideo_answer_command(verb);
  if (verb == "mcvideo-chat") {
    if (argument.empty()) throw std::runtime_error("mcvideo-chat needs a chat group");
    return mcvideo_group_call(false, argument);
  }
  if (verb == "mcvideo-transmit") return mcvideo_request_transmission();
  if (verb == "mcvideo-remote-transmit" || verb == "mcvideo-remote-cancel")
    return mcvideo_remote_transmission(argument, verb == "mcvideo-remote-transmit");
  if (verb == "mcvideo-release") return mcvideo_release_transmission();
  if (verb == "mcvideo-receive" || verb == "mcvideo-stop-receiving" || verb == "mcvideo-end")
    return mcvideo_reception_command(verb);
  if (verb == "mcvideo-hangup") return mcvideo_hangup_command();
  throw std::runtime_error("unknown MCVideo command");
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.1: mcvideo-private <MCVideo ID> [auto|manual|force-auto] [no-control] [emergency].
void Client::mcvideo_private_command(const std::string &argument, std::istringstream &words) {
  std::string mode, extra;
  bool no_control = false, emergency = false;
  words >> mode;
  while (words >> extra) {
    if (extra == "no-control")
      no_control = true;
    else if (extra == "emergency")
      emergency = true;
    else
      throw std::runtime_error("unknown mcvideo-private option " + extra);
  }
  return mcvideo_private_call(argument, mode, no_control, emergency);
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 12.1A.1.1 and 12.1A.1.2: an ad hoc group emergency alert, or its cancellation.
void Client::mcvideo_adhoc_alert_command(const std::string &argument, std::istringstream &words) {
  std::string criteria = argument;
  if (argument != "cancel")
    for (std::string more; words >> more;) criteria += " " + more;
  return mcvideo_adhoc_alert(criteria);
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 20.4.2.1.2 and 20.4.2.1.3: bind an alias to groups, or unbind it.
void Client::mcvideo_alias_binding_command(const std::string &verb, const std::string &argument,
                                           std::istringstream &words) {
  std::string list;
  words >> list;
  std::vector<std::string> groups;
  for (std::size_t start = 0; start < list.size();) {
    const auto end = std::min(list.find(',', start), list.size());
    if (end > start) groups.push_back(list.substr(start, end - start));
    start = end + 1;
  }
  return mcvideo_alias_binding(verb == "mcvideo-fa-bind", argument, groups);
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 21.2.1.1, 21.3.1.1 and 21.2.1.2: create a group or user regroup using a
// preconfigured group, or remove one.
void Client::mcvideo_regroup_request_command(const std::string &argument, std::istringstream &words) {
  std::string tgi, preconfigured, kind, list;
  words >> tgi;
  if (argument == "remove") return mcvideo_regroup_command("remove", tgi, {}, {}, {});
  words >> preconfigured >> kind >> list;
  if (argument != "create" || tgi.empty() || preconfigured.empty() || (kind != "groups" && kind != "users") ||
      list.empty())
    throw std::runtime_error(
        "mcvideo-regroup create <TGI> <preconfigured group> groups|users <URI>,... or remove <TGI>");
  std::vector<std::string> entries;
  for (std::size_t start = 0; start <= list.size();) {
    const auto end = std::min(list.find(',', start), list.size());
    if (end > start) entries.push_back(list.substr(start, end - start));
    start = end + 1;
  }
  return kind == "groups" ? mcvideo_regroup_command("create", tgi, preconfigured, entries, {})
                          : mcvideo_regroup_command("create", tgi, preconfigured, {}, entries);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 22.2.2.1.1: an ad hoc group call to MCVideo users or by criteria.
void Client::mcvideo_adhoc_command(const std::string &argument, std::istringstream &words) {
  std::vector<std::string> targets;
  std::string criteria;
  if (argument == "criteria")
    std::getline(words >> std::ws, criteria);
  else {
    if (!argument.empty()) targets.push_back(argument);
    for (std::string more; words >> more;) targets.push_back(more);
  }
  return mcvideo_adhoc_call(targets, criteria);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.2: mcvideo-answer or mcvideo-reject, the invited user's answer to a ringing call.
void Client::mcvideo_answer_command(const std::string &verb) {
  if (!mcvideo_call_ || !mcvideo_call_->pending_answer) throw std::runtime_error("no ringing MCVideo call");
  auto response = *std::exchange(mcvideo_call_->pending_answer, std::nullopt);
  const auto dialog = mcvideo_call_->dialog;
  if (verb == "mcvideo-reject") {
    // TS 24.281 clause 10.2.2.2.2 steps 1 and 2: the invited user declines: a reject code with the warning text of
    // clause 4.4.2 ("110 user declined the call invitation"), or, for a user the MCVideo user profile authorises
    // to restrict the reason for failure (<allow-failure-restriction>, TS 24.484), 480 without a warning text.
    response = SipResponse{};
    response.status_code = 480;
    if (!mcvideo_profile_allows("allow-failure-restriction"))
      response.headers = {{"Warning", "399 mcx \"" + warning_text(mcvideo_service, Warning::declined) + "\""}};
    mcvideo_end_call("declined");
  } else {
    mcvideo_call_->state = "joining";
  }
  return sip_.answer(dialog, response);
}

//------------------------------------------------------------------------------
// TS 24.581 clause 6.2.4: mcvideo-receive, mcvideo-stop-receiving or mcvideo-end: transmission control requests
// about reception, and the end of this user's transmission.
void Client::mcvideo_reception_command(const std::string &verb) {
  if (!mcvideo_call_) throw std::runtime_error("no MCVideo call");
  if (verb == "mcvideo-end") {
    // TS 24.581 clause 6.2.4.5.3: Transmission End Request (TS 24.581 clause 9.2.20) instead of Transmission
    // Release.
    if (!mcvideo_call_->granted) throw std::runtime_error("not transmitting MCVideo");
    mcvideo_call_->granted = false;
    return mcvideo_send_control(transmission::Type::EndRequest);
  }
  return mcvideo_send_control(verb == "mcvideo-receive" ? transmission::Type::ReceiveMediaRequest
                                                        : transmission::Type::ReceptionEndRequest);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 15.2.1.3 step 1 for an ambient viewing call: mcvideo-hangup releases the MCVideo call.
void Client::mcvideo_hangup_command() {
  if (!mcvideo_call_) throw std::runtime_error("no MCVideo call");
  // TS 24.281 clause 15.2.1.3 step 1: an ambient viewing call is released only with the release capability.
  if (!mcvideo_call_->may_release) throw std::runtime_error("ambient viewing call release not allowed");
  const auto dialog = mcvideo_call_->dialog;
  mcvideo_end_call("hangup");
  return sip_.bye(dialog);
}

//------------------------------------------------------------------------------
// Helper: publish the MCVideo state in the status document.
void Client::mcvideo_status() {
  mcvideo_call_status();
  status_["mcvideo_alerting"] = mcvideo_alerting_;
  status_["mcvideo_alerts"] = mcvideo_alerts_;
  status_["mcvideo_priority_result"] = mcvideo_alert_result_;
  status_["mcvideo_authorized"] = mcvideo_authorized_;
  status_["mcvideo_affiliated"] = mcvideo_affiliated_;
  status_["mcvideo_affiliations"] = mcvideo_groups_;
  status_["mcvideo_affiliation_subscribed"] = !mcvideo_subscription_.empty();
  status_["mcvideo_affiliation_p_id"] = {{"sent", mcvideo_sent_pid_}, {"received", mcvideo_received_pid_}};
  status_["mcvideo_last_result"] = mcvideo_last_result_;
  status_["mcvideo_functional_aliases"] = mcvideo_aliases_;
  status_["mcvideo_functional_alias_in_use"] = mcvideo_alias_in_use_;
  status_["mcvideo_functional_alias_result"] = mcvideo_fa_result_;
  status_["mcvideo_functional_alias_redirect"] = mcvideo_redirect_;
  status_["mcvideo_functional_alias_status_known"] = mcvideo_alias_status_known_;
  status_["mcvideo_functional_alias_binding_result"] = mcvideo_binding_result_;
  status_["mcvideo_selected_group"] = mcvideo_group();
  status_["mcvideo_location"] = mcvideo_location_status();
  status_["mcvideo_adhoc_alert_state"] = mcvideo_ah_mea_;
  status_["mcvideo_adhoc_alert_group"] = mcvideo_adhoc_alert_group_;
  status_["mcvideo_received_adhoc_alerts"] = mcvideo_adhoc_alerts_;
  status_["mcvideo_adhoc_alert_result"] = mcvideo_adhoc_alert_result_;
  status_["mcvideo_group_selection_result"] = mcvideo_selection_result_;
  status_["mcvideo_group_selection"] = mcvideo_selection_;
  status_["mcvideo_alias_users"] = mcvideo_alias_users_;
  status_["mcvideo_functional_alias_notified"] = mcvideo_fa_notified_;
  {
    Json followed = Json::array();
    for (const auto &[dialog, alias] : mcvideo_alias_dialogs_) followed.push_back(alias);
    status_["mcvideo_functional_alias_subscriptions"] = followed;
  }
}

} // namespace mcx
