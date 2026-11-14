//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo service settings determination (TS 24.281 clause 7.2.4) and the affiliation procedures towards other users:
// the status determination of another user (clause 8.2.1.3), an affiliation status change for another user in
// mandatory mode (clause 8.2.1.2), and the negotiated mode, sending (clause 8.2.1.4) and receiving (clause 8.2.1.5).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), IETF RFC 6665, RFC 4354, RFC 3856, RFC 3903, RFC 3428.

#include "client.hpp"

namespace mcx {

namespace {
constexpr const char *affiliation_command_mime = "application/vnd.3gpp.mcvideo-affiliation-command+xml";
constexpr const char *forever = "4294967295";

// TS 24.281 annex F.4: a <command-list> with the groups to affiliate to or de-affiliate from.
std::string affiliation_command_xml(const std::vector<std::string> &affiliate, const std::vector<std::string> &leave) {
  std::string xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    "<command-list xmlns=\"urn:3gpp:ns:affiliationCommand:1.0\">";
  const auto list = [&xml](const char *name, const std::vector<std::string> &groups) {
    if (groups.empty()) return;
    xml += std::string("<") + name + ">";
    for (const auto &group : groups) xml += "<group>" + xml_escape(group) + "</group>";
    xml += std::string("</") + name + ">";
  };
  list("affiliate", affiliate);
  list("de-affiliate", leave);
  return xml + "</command-list>";
}

// The text of each <group> inside <name> (the namespace prefix, if any, is ignored).
std::vector<std::string> command_groups(const std::string &xml, const std::string &name) {
  std::vector<std::string> out;
  const auto open = xml.find(":" + name + ">") != std::string::npos ? xml.find(":" + name + ">")
                                                                     : xml.find("<" + name + ">");
  if (open == std::string::npos) return out;
  const auto close = xml.find(name + ">", open + name.size() + 2);
  const auto section = xml.substr(open, close == std::string::npos ? std::string::npos : close - open);
  for (std::size_t at = 0; (at = section.find("group>", at)) != std::string::npos;) {
    const auto start = at + 6;
    const auto end = section.find('<', start);
    if (end == std::string::npos) break;
    if (section[at - 1] != '/') {
      std::string value = section.substr(start, end - start);
      if (!value.empty()) out.push_back(value);
    }
    at = end;
  }
  return out;
}
} // namespace

//------------------------------------------------------------------------------
// TS 24.281 clause 7.2.4: mcvideo-settings-subscribe [fetch], mcvideo-settings-resubscribe,
// mcvideo-settings-unsubscribe: the SUBSCRIBE to this user's MCVideo service settings (event package poc-settings).
void Client::mcvideo_settings_command(const std::string &verb, std::istringstream &words) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo service settings require authorisation");
  if (verb == "mcvideo-settings-subscribe") {
    std::string option;
    words >> option;
    if (!option.empty() && option != "fetch") throw std::runtime_error("mcvideo-settings-subscribe [fetch]");
    McpttInfo info; // step 2: <mcvideo-request-uri> set to this user's MCVideo ID
    info.request_uri = mcvideo_user();
    info.client_id = client_id();
    const bool fetch = option == "fetch"; // step 7: Expires 0 fetches the current state only
    const auto dialog = sip_.subscribe(
        mcvideo_service_uri(), "poc-settings", fetch ? 0U : 4294967295U,
        {{"P-Preferred-Service", mcvideo_service.icsi}, {"Accept", "application/poc-settings+xml"}},
        [this](const SipResponse &response) {
          event("mcvideo_settings_subscription", {{"status", response.status_code}});
        },
        mcvideo_service.info_mime, service_info_xml(info, mcvideo_service));
    mcvideo_settings_subscription_ = dialog;
    mcvideo_extra_subscriptions_[dialog] = "poc-settings";
    return;
  }
  if (mcvideo_settings_subscription_.empty()) throw std::runtime_error("no MCVideo settings subscription");
  const bool resubscribe = verb == "mcvideo-settings-resubscribe";
  if (!resubscribe && verb != "mcvideo-settings-unsubscribe") throw std::runtime_error("unknown MCVideo settings command");
  // In-dialog: Event poc-settings, Accept, Expires 4294967295 (re-subscribe) or 0 (de-subscribe).
  sip_.request_in_dialog(mcvideo_settings_subscription_, "SUBSCRIBE", "", "",
                         {{"Event", "poc-settings"},
                          {"Accept", "application/poc-settings+xml"},
                          {"Expires", resubscribe ? forever : "0"}},
                         [this, resubscribe](const SipResponse &response) {
                           event(resubscribe ? "mcvideo_settings_resubscribed" : "mcvideo_settings_unsubscribed",
                                 {{"status", response.status_code}});
                         });
  if (!resubscribe) mcvideo_settings_subscription_.clear();
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 8.2.1.3, 8.2.1.2 and 8.2.1.4: the affiliation of another MCVideo user (or of this one, with a
// subscription of its own beside the client's):
//   mcvideo-affiliation-subscribe <MCVideo ID> [fetch]   mcvideo-affiliation-unsubscribe <MCVideo ID>
//   mcvideo-affiliate-remote <MCVideo ID> <group> affiliate|deaffiliate mandatory|negotiated
//   mcvideo-affiliation-accept   mcvideo-affiliation-decline   (a received negotiated-mode request, clause 8.2.1.5)
void Client::mcvideo_target_affiliation_command(const std::string &verb, std::istringstream &words) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo affiliation requires authorisation");
  std::string target;
  if (verb == "mcvideo-affiliation-accept" || verb == "mcvideo-affiliation-decline")
    return mcvideo_affiliation_command_answer(verb == "mcvideo-affiliation-accept");
  words >> target;
  if (target.empty()) throw std::runtime_error(verb + " needs an MCVideo ID");
  if (verb == "mcvideo-affiliation-subscribe") {
    std::string option;
    words >> option;
    McpttInfo info; // step 2: <mcvideo-request-uri> set to the targeted user's MCVideo ID
    info.request_uri = target;
    info.client_id = client_id();
    const bool fetch = option == "fetch";
    // Step 7: for this user's groups at this client, the per-client simple-filter (clause 8.3.2.2).
    std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
    if (target == mcvideo_user()) parts.push_back({"application/simple-filter+xml", mcvideo_client_filter()});
    const auto [type, body] = message_body(parts, "mcvideo-" + random_id());
    const auto dialog = sip_.subscribe(
        mcvideo_service_uri(), "presence", fetch ? 0U : 4294967295U,
        {{"P-Preferred-Service", mcvideo_service.icsi}, {"Accept", "application/pidf+xml"}},
        [this, target](const SipResponse &response) {
          event("mcvideo_target_affiliation_subscription", {{"target", target}, {"status", response.status_code}});
        },
        type, body);
    mcvideo_extra_subscriptions_[dialog] = target;
    return;
  }
  if (verb == "mcvideo-affiliation-unsubscribe") {
    const auto found = std::find_if(mcvideo_extra_subscriptions_.begin(), mcvideo_extra_subscriptions_.end(),
                                    [&](const auto &entry) { return entry.second == target; });
    if (found == mcvideo_extra_subscriptions_.end()) throw std::runtime_error("no affiliation subscription to " + target);
    sip_.request_in_dialog(found->first, "SUBSCRIBE", "", "",
                           {{"Event", "presence"}, {"Accept", "application/pidf+xml"}, {"Expires", "0"}},
                           [this, target](const SipResponse &response) {
                             event("mcvideo_target_affiliation_unsubscribed",
                                   {{"target", target}, {"status", response.status_code}});
                           });
    return;
  }
  if (verb != "mcvideo-affiliate-remote") throw std::runtime_error("unknown MCVideo affiliation command");
  std::string group, operation, mode;
  words >> group >> operation >> mode;
  if (group.empty() || (operation != "affiliate" && operation != "deaffiliate") ||
      (mode != "mandatory" && mode != "negotiated"))
    throw std::runtime_error("mcvideo-affiliate-remote <MCVideo ID> <group> affiliate|deaffiliate mandatory|negotiated");
  const bool affiliate = operation == "affiliate";
  McpttInfo info;
  info.request_uri = target;
  info.client_id = client_id();
  if (mode == "negotiated") {
    // Clause 8.2.1.4: a MESSAGE with the affiliation command for the target user.
    const auto [type, body] = message_body(
        {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
         {affiliation_command_mime,
          affiliation_command_xml(affiliate ? std::vector{group} : std::vector<std::string>{},
                                  affiliate ? std::vector<std::string>{} : std::vector{group})}},
        "mcvideo-" + random_id());
    sip_.request("MESSAGE", mcvideo_service_uri(), type, body, mcvideo_call_headers(),
                 [this, target](const SipResponse &response) {
                   // On a 2xx: the request was delivered to a client of the target user.
                   event("mcvideo_affiliation_request_delivered", {{"target", target}, {"status", response.status_code}});
                 });
    return;
  }
  // Clause 8.2.1.2 in mandatory mode: a PUBLISH for the targeted user's client, its groups changed. The client ID and
  // the groups come from the targeted user's affiliation notification (mcvideo-affiliation-subscribe first).
  const auto known = mcvideo_target_affiliations_.find(target);
  if (known == mcvideo_target_affiliations_.end() || known->second.client_id.empty())
    throw std::runtime_error("the affiliation of " + target + " is not known: mcvideo-affiliation-subscribe first");
  auto affiliation = Affiliation{target, known->second.client_id, random_id(), known->second.groups, {}};
  affiliation.mcvideo = true;
  auto &groups = affiliation.groups;
  groups.erase(std::remove(groups.begin(), groups.end(), group), groups.end());
  if (affiliate) groups.push_back(group);
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  // Steps 4 and 5: Expires 0 when the targeted user keeps no group, else 4294967295 with the PIDF.
  if (!groups.empty()) parts.push_back({"application/pidf+xml", affiliation_xml(affiliation, false)});
  mcvideo_request("presence", parts, {{"Expires", groups.empty() ? "0" : forever}},
                  [this, target, operation](const SipResponse &response) {
                    event("mcvideo_remote_affiliation", {{"target", target},
                                                         {"operation", operation},
                                                         {"status", response.status_code}});
                  });
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.2.1.6: the subscription to a group's dynamic data. The initial SUBSCRIBE (event package
// presence, RFC 3856): to the participating function's PSI (step 1), with the mcvideo-info body naming the group
// (step 2), P-Preferred-Service (step 3), Expires 4294967295 (step 4) or 0 to fetch the current state only (step 5),
// Accept application/pidf+xml (step 6) and the per-group dynamic data simple-filter of clause 8.3.2.2 naming the group
// and the items asked for (step 7; none asks for all). In the dialog: Expires 4294967295 (re-subscribe, step 1) or 0
// (de-subscribe, step 2), with the Accept (step 3).
void Client::mcvideo_gdd_command(const std::string &verb, std::istringstream &words) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo group dynamic data requires authorisation");
  std::string group;
  words >> group;
  if (group.empty()) throw std::runtime_error(verb + " <group>");
  if (verb == "mcvideo-gdd-subscribe") {
    if (mcvideo_gdd_dialogs_.contains(group)) throw std::runtime_error("already subscribed to " + group);
    bool fetch = false;
    GroupDynamicDataFilter filter;
    filter.group = group;
    filter.id = "gdd-" + random_id().substr(0, 8);
    for (std::string item; words >> item;) {
      if (item == "fetch") fetch = true;
      else if (item == "status") filter.group_status = true;
      else if (item == "broadcast") filter.broadcast_alias = true;
      else if (item == "regroup") filter.regroup_alias = true;
      else if (item == "call") filter.call_ongoing = true;
      else if (item == "affiliated") filter.affiliated = true;
      else throw std::runtime_error("mcvideo-gdd-subscribe <group> [fetch] [status] [broadcast] [regroup] [call] [affiliated]");
    }
    McpttInfo info;
    info.request_uri = group; // step 2
    info.client_id = client_id();
    const auto [type, body] =
        message_body({{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                      {"application/simple-filter+xml", group_dynamic_data_filter_xml(filter)}}, // step 7
                     "mcvideo-" + random_id());
    const auto dialog = sip_.subscribe(
        mcvideo_service_uri(), "presence", fetch ? 0U : 4294967295U, // steps 1, 4, 5
        {{"P-Preferred-Service", mcvideo_service.icsi}, {"Accept", "application/pidf+xml"}}, // steps 3, 6
        [this, group](const SipResponse &response) {
          if (response.status_code < 200) return;
          mcvideo_gdd_[group]["subscription_status"] = response.status_code;
          status_["mcvideo_group_dynamic_data"] = mcvideo_gdd_;
          if (response.status_code >= 300) mcvideo_gdd_dialogs_.erase(group);
          event("mcvideo_gdd_subscription", {{"group", group}, {"status", response.status_code}});
        },
        type, body);
    mcvideo_extra_subscriptions_[dialog] = "gdd:" + group;
    if (!fetch) mcvideo_gdd_dialogs_[group] = dialog;
    return;
  }
  const auto found = mcvideo_gdd_dialogs_.find(group);
  if (found == mcvideo_gdd_dialogs_.end()) throw std::runtime_error("no group dynamic data subscription to " + group);
  const bool resubscribe = verb == "mcvideo-gdd-resubscribe";
  if (!resubscribe && verb != "mcvideo-gdd-unsubscribe") throw std::runtime_error("unknown group dynamic data command");
  sip_.request_in_dialog(found->second, "SUBSCRIBE", "", "",
                         {{"Event", "presence"},
                          {"Accept", "application/pidf+xml"},
                          {"Expires", resubscribe ? "4294967295" : "0"}},
                         [this, group, resubscribe](const SipResponse &response) {
                           event(resubscribe ? "mcvideo_gdd_resubscribed" : "mcvideo_gdd_unsubscribed",
                                 {{"group", group}, {"status", response.status_code}});
                         });
  if (!resubscribe) mcvideo_gdd_dialogs_.erase(found);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.2.1.6: a NOTIFY with the application/pidf+xml body of per-group dynamic data (clause 8.3.1.2):
// the group's status (with ModifiedBy), its broadcast and regroup aliases, whether a call is ongoing and its
// affiliated members are taken as the group's dynamic data.
SipResponse Client::mcvideo_gdd_notify(const SipMessage &message, const std::string &group) {
  SipResponse response;
  response.status_code = 200;
  std::string body;
  if (message.parts.empty()) {
    if (message.content_type.starts_with("application/pidf+xml")) body = message.body;
  } else {
    for (const auto &part : message.parts)
      if (part.content_type.starts_with("application/pidf+xml")) body = part.body;
  }
  if (body.empty()) return response; // RFC 6665: a NOTIFY without state (e.g. a final one)
  try {
    const auto data = parse_group_dynamic_data(body);
    if (!data.group.empty() && data.group != group) throw std::runtime_error("dynamic data of another group");
    Json members = Json::array();
    for (const auto &member : data.members) {
      Json clients = Json::array();
      for (const auto &client : member.clients) clients.push_back(client.client);
      members.push_back({{"id", member.id}, {"clients", clients}, {"functional_aliases", member.functional_aliases}});
    }
    auto &entry = mcvideo_gdd_[group];
    entry["group_status"] = data.group_status;
    entry["modified_by"] = data.modified_by;
    entry["broadcast_alias"] = data.broadcast_alias;
    entry["regroup_alias"] = data.regroup_alias;
    entry["call_ongoing"] = data.call_ongoing ? Json(*data.call_ongoing) : Json();
    entry["members"] = members;
    entry["notifications"] = entry.value("notifications", 0) + 1;
    status_["mcvideo_group_dynamic_data"] = mcvideo_gdd_;
    event("mcvideo_gdd_notified", {{"group", group}, {"group_status", data.group_status}, {"members", members.size()}});
  } catch (const std::exception &error) {
    event("mcvideo_gdd_unread", {{"group", group}, {"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// RFC 6665 section 4.1.3: the NOTIFYs of the subscriptions above: poc-settings (TS 24.281 clause 7.2.4) and another
// user's affiliation (clause 8.2.1.3), whose client ID and groups are kept for a mandatory-mode change.
SipResponse Client::mcvideo_extra_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto found = mcvideo_extra_subscriptions_.find(message.dialog_id);
  if (found == mcvideo_extra_subscriptions_.end()) {
    response.status_code = 481;
    return response;
  }
  const auto subject = found->second;
  if (get_header(message.headers, "Subscription-State").starts_with("terminated"))
    mcvideo_extra_subscriptions_.erase(found);
  if (subject == "poc-settings") {
    event("mcvideo_settings_notified", {{"body", !message.body.empty() || !message.parts.empty()}});
    return response;
  }
  if (subject.starts_with("gdd:")) {
    const auto group = subject.substr(4);
    if (get_header(message.headers, "Subscription-State").starts_with("terminated")) mcvideo_gdd_dialogs_.erase(group);
    return mcvideo_gdd_notify(message, group);
  }
  const auto &body = message.parts.empty() ? message.body : message.parts.front().body;
  if (body.empty()) return response;
  try {
    auto affiliation = parse_affiliation(body);
    mcvideo_target_affiliations_[subject] = affiliation;
    event("mcvideo_target_affiliation", {{"target", subject}, {"groups", affiliation.groups}});
  } catch (const std::exception &error) {
    event("mcvideo_target_affiliation_unread", {{"target", subject}, {"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.2.1.5: a negotiated-mode affiliation status change request: 200 (OK) at once (step 1), then the
// user's confirmation (step 2); the groups are kept until mcvideo-affiliation-accept or -decline.
SipResponse Client::mcvideo_affiliation_command_message(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  std::string body;
  for (const auto &part : message.parts)
    if (part.content_type == affiliation_command_mime) body = part.body;
  if (body.empty() && message.content_type == affiliation_command_mime) body = message.body;
  mcvideo_affiliation_command_ = {{"affiliate", command_groups(body, "affiliate")},
                                  {"de-affiliate", command_groups(body, "de-affiliate")},
                                  {"from", message.from_uri}};
  status_["mcvideo_affiliation_command"] = mcvideo_affiliation_command_;
  event("mcvideo_affiliation_command_received", mcvideo_affiliation_command_);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.2.1.5 step 3: the user accepts (affiliation and de-affiliation as in clause 8.2.1.2) or declines.
void Client::mcvideo_affiliation_command_answer(bool accept) {
  if (!mcvideo_affiliation_command_.is_object()) throw std::runtime_error("no MCVideo affiliation request");
  const auto command = std::exchange(mcvideo_affiliation_command_, Json());
  status_["mcvideo_affiliation_command"] = nullptr;
  event(accept ? "mcvideo_affiliation_command_accepted" : "mcvideo_affiliation_command_declined");
  if (!accept) return;
  for (const auto &group : command.value("affiliate", std::vector<std::string>{})) mcvideo_affiliate(true, group);
  if (!command.value("de-affiliate", std::vector<std::string>{}).empty()) mcvideo_affiliate(false);
}

} // namespace mcx
