//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData service settings and affiliation procedures, MCData client side (member functions of Client): the
// settings-only PUBLISH (TS 24.282 clause 7.2.3) and the determination of the service settings (clause 7.2.4); the
// affiliation to and de-affiliation from given groups (clause 8.2.2); the affiliation of another user: its status
// determination (clause 8.2.3), a change in mandatory mode (clause 8.2.2) and in negotiated mode, sending (clause
// 8.2.4) and receiving (clause 8.2.5); the subscription to group dynamic data (clause 8.2.7).
//
// The procedures are those of TS 24.281 for MCVideo (mcvideo/settings_affiliation.cpp) in the MCData names: the
// mcdata-info body (annex D.1), the MCData ICSI, application/vnd.3gpp.mcdata-affiliation-command+xml (annex D.3), the
// MCData PIDF of clause 8.4.1.
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), IETF RFC 6665, RFC 4354, RFC 3856, RFC 3903, RFC 3428, RFC 4661.

#include "client.hpp"

namespace mcx {

namespace {
constexpr const char *mcdata_affiliation_command_mime = "application/vnd.3gpp.mcdata-affiliation-command+xml";
constexpr const char *mcdata_forever = "4294967295";

// TS 24.282 annex D.3: a <command-list> with the groups to affiliate to or de-affiliate from (the affiliationCommand
// namespace of every MC service).
std::string mcdata_command_xml(const std::vector<std::string> &affiliate, const std::vector<std::string> &leave) {
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

// The text of each <group> inside <name> of a <command-list> (any namespace prefix).
std::vector<std::string> mcdata_command_groups(const std::string &xml, const std::string &name) {
  std::vector<std::string> out;
  auto open = xml.find(":" + name + ">");
  if (open == std::string::npos) open = xml.find("<" + name + ">");
  if (open == std::string::npos) return out;
  const auto close = xml.find("/" + name + ">", open);
  const auto inner = xml.substr(open, close == std::string::npos ? std::string::npos : close - open);
  for (std::size_t at = 0;;) {
    auto start = inner.find("group>", at);
    if (start == std::string::npos) break;
    start += 6;
    const auto end = inner.find("</", start);
    if (end == std::string::npos) break;
    // "<group>" and "<prefix:group>", not "</group>" (the closing tag precedes no text)
    if (start >= 7 && inner[start - 7] != '/') out.push_back(inner.substr(start, end - start));
    at = end + 1;
  }
  return out;
}

// The body of the first part of `type` (or the whole body of that type), or empty.
std::string mcdata_part(const SipMessage &message, std::string_view type) {
  for (const auto &part : message.parts)
    if (part.content_type.starts_with(type)) return part.body;
  return message.content_type.starts_with(type) ? message.body : std::string{};
}
} // namespace

//------------------------------------------------------------------------------
// TS 24.282 clauses 7.2.2 6) and 7.2.3 4): the application/poc-settings+xml of this client, with only a) the
// <selected-user-profile-index> of the selected MCData user profile (TS 24.484): MCData publishes no answer mode.
std::string Client::mcdata_poc_settings() const {
  const unsigned index = mcdata_profile_index_.value_or(cfg_.at("mcdata").value("profile_index", 0U));
  return "<?xml version=\"1.0\"?><poc-settings xmlns=\"urn:oma:params:xml:ns:poc:poc-settings\" "
         "xmlns:mc=\"urn:3gpp:mcsSettings:1.0\"><entity id=\"" +
         xml_escape(client_id()) + "\"><mc:selected-user-profile-index><mc:user-profile-index>" +
         std::to_string(index) + "</mc:user-profile-index></mc:selected-user-profile-index></entity></poc-settings>";
}

//------------------------------------------------------------------------------
// TS 24.282 clause 7.2.3: the SIP PUBLISH for the MCData service settings only (remove: Expires 0, which removes them
// and logs the client off the MCData service, clause 7.2.1A NOTE 3):
//   1) clause 7.2.1A: the participating function's PSI, the MCData ICSI, Event poc-settings, Expires 4294967295;
//   2), 3) the mcdata-info body with <mcdata-request-uri> (this user's MCData ID) and <mcdata-client-id> (encrypted
//      when confidentiality protection is enabled);
//   4) the application/poc-settings+xml body (mcdata_poc_settings).
// RFC 3903: the publication is modified with the entity-tag of the last one (that of the service authorisation
// PUBLISH of clause 7.2.2, which carries the settings too).
void Client::mcdata_settings_publish(bool remove) {
  mcdata::Info info;
  info.request_uri = mcdata_user();
  info.client_id = client_id();
  std::vector<SipHeader> headers{{"Expires", remove ? "0" : mcdata_forever}};
  const bool modify = !mcdata_auth_etag_.empty();
  if (modify) headers.push_back({"SIP-If-Match", mcdata_auth_etag_});
  // (mcdata_request protects the bodies: the signalling sealed, the XML encrypted when enabled)
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)},
                              {"application/poc-settings+xml", mcdata_poc_settings()}};
  mcdata_request("poc-settings", parts, headers, [this, remove, modify](const SipResponse &response) {
    mcdata_settings_result_ = {{"action", remove ? "remove" : "publish"},
                               {"status", response.status_code},
                               {"warning", std::string(get_header(response.headers, "Warning"))}};
    if (response.status_code != 200) {
      // RFC 3903 section 5: after a 412 to a modification, discard the entity-tag and publish anew.
      if (modify && response.status_code == 412 && !remove) {
        mcdata_auth_etag_.clear();
        schedule([this] {
          if (mcdata_authorized_) mcdata_settings_publish(false);
        });
      }
      event("mcdata_settings_publication_refused", {{"status", response.status_code}});
      return;
    }
    if (remove) {
      // TS 24.282 clause 7.2.1A NOTE 3: logged off the MCData service, the affiliations removed with it.
      mcdata_auth_etag_.clear();
      mcdata_affiliation_etag_.clear();
      mcdata_authorized_ = mcdata_affiliated_ = false;
      mcdata_groups_.clear();
      event("mcdata_settings_removed");
      return;
    }
    if (const auto etag = get_header(response.headers, "SIP-ETag"); !etag.empty()) mcdata_auth_etag_ = etag;
    event("mcdata_settings_published");
  });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 7.2.4: a NOTIFY with an application/poc-settings+xml body: for each MCData client named by the
// "id" of an <entity> (RFC 4354) the client caches 1) its <am-settings> as the current Answer-mode indication of that
// client and 2) its <selected-user-profile-index> as the active MCData user profile of that client.
SipResponse Client::mcdata_settings_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto body = mcdata_part(message, "application/poc-settings+xml");
  if (body.empty()) {
    event("mcdata_settings_notified", {{"body", false}});
    return response;
  }
  try {
    const auto entities = parse_poc_settings(body);
    for (const auto &entity : entities) {
      auto &cached = mcdata_service_settings_[entity.id];
      if (!cached.is_object()) cached = Json::object();
      if (!entity.answer_mode.empty()) cached["answer_mode"] = entity.answer_mode;
      if (entity.profile_index) cached["profile_index"] = *entity.profile_index;
      cached["own"] = entity.id == client_id();
    }
    event("mcdata_settings_notified", {{"body", true}, {"clients", entities.size()}});
  } catch (const std::exception &error) {
    // An unreadable document leaves the cache as it was (RFC 6665: the NOTIFY is still answered).
    event("mcdata_settings_unread", {{"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// The MCData service settings commands:
//   mcdata-settings-publish [remove]                 (clause 7.2.3)
//   mcdata-settings-subscribe [fetch]                (clause 7.2.4: SUBSCRIBE poc-settings for this user)
//   mcdata-settings-resubscribe | mcdata-settings-unsubscribe   (in the subscription dialog)
//   mcdata-profile <user profile index>              (the user selects an MCData user profile: the setting the next
//                                                     settings PUBLISH carries)
void Client::mcdata_settings_command(const std::string &verb, std::istringstream &words) {
  if (verb == "mcdata-profile") {
    std::string index;
    words >> index;
    if (index.empty() || index.find_first_not_of("0123456789") != std::string::npos)
      throw std::runtime_error("mcdata-profile <user profile index>");
    mcdata_profile_index_ = static_cast<unsigned>(std::stoul(index));
    event("mcdata_profile_selected", {{"index", *mcdata_profile_index_}});
    return;
  }
  if (!mcdata_authorized_) throw std::runtime_error("MCData service settings require authorisation");
  if (verb == "mcdata-settings-publish") {
    std::string option;
    words >> option;
    if (!option.empty() && option != "remove") throw std::runtime_error("mcdata-settings-publish [remove]");
    return mcdata_settings_publish(option == "remove");
  }
  if (verb == "mcdata-settings-subscribe") {
    std::string option;
    words >> option;
    if (!option.empty() && option != "fetch") throw std::runtime_error("mcdata-settings-subscribe [fetch]");
    mcdata::Info info; // step 2: <mcdata-request-uri> set to this user's MCData ID
    info.request_uri = mcdata_user();
    info.client_id = client_id();
    const bool fetch = option == "fetch"; // Expires 0 fetches the current state only
    const auto dialog = sip_.subscribe(
        mcdata_service(), "poc-settings", fetch ? 0U : 4294967295U,
        {{"P-Preferred-Service", mcdata::icsi}, {"Accept", "application/poc-settings+xml"}},
        [this](const SipResponse &response) {
          event("mcdata_settings_subscription", {{"status", response.status_code}});
        },
        mcdata::info_mime,
        mcdata_encrypt_xml({{mcdata::info_mime, mcdata::info_xml(info)}}, mcdata_xml_key()).front().body);
    mcdata_settings_subscription_ = dialog;
    mcdata_extra_subscriptions_[dialog] = "poc-settings";
    return;
  }
  if (mcdata_settings_subscription_.empty()) throw std::runtime_error("no MCData settings subscription");
  const bool resubscribe = verb == "mcdata-settings-resubscribe";
  if (!resubscribe && verb != "mcdata-settings-unsubscribe") throw std::runtime_error("unknown MCData settings command");
  sip_.request_in_dialog(mcdata_settings_subscription_, "SUBSCRIBE", "", "",
                         {{"Event", "poc-settings"},
                          {"Accept", "application/poc-settings+xml"},
                          {"Expires", resubscribe ? mcdata_forever : "0"}},
                         [this, resubscribe](const SipResponse &response) {
                           event(resubscribe ? "mcdata_settings_resubscribed" : "mcdata_settings_unsubscribed",
                                 {{"status", response.status_code}});
                         });
  if (!resubscribe) mcdata_settings_subscription_.clear();
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 8.2.3, 8.2.2, 8.2.4 and 8.2.5: the affiliation of another MCData user (or of this one, with a
// subscription of its own beside the client's):
//   mcdata-affiliation-subscribe <MCData ID> [fetch]   mcdata-affiliation-unsubscribe <MCData ID>
//   mcdata-affiliate-remote <MCData ID> <group> affiliate|deaffiliate mandatory|negotiated
//   mcdata-affiliation-accept [<group>...]   mcdata-affiliation-decline   (a received negotiated-mode request)
void Client::mcdata_target_affiliation_command(const std::string &verb, std::istringstream &words) {
  if (!mcdata_authorized_) throw std::runtime_error("MCData affiliation requires authorisation");
  if (verb == "mcdata-affiliation-accept" || verb == "mcdata-affiliation-decline") {
    std::set<std::string> only;
    for (std::string group; words >> group;) only.insert(group);
    return mcdata_affiliation_command_answer(verb == "mcdata-affiliation-accept", only);
  }
  std::string target;
  words >> target;
  if (target.empty()) throw std::runtime_error(verb + " needs an MCData ID");
  if (verb == "mcdata-affiliation-subscribe") {
    std::string option;
    words >> option;
    mcdata::Info info; // clause 8.2.3 step 2: <mcdata-request-uri> the targeted user's MCData ID
    info.request_uri = target;
    info.client_id = client_id();
    const bool fetch = option == "fetch";
    // Step 7: for this user's groups at this client, the per-client simple-filter (clause 8.4.2.2).
    std::vector<BodyPart> parts =
        mcdata_encrypt_xml({{mcdata::info_mime, mcdata::info_xml(info)}}, mcdata_xml_key());
    if (target == mcdata_user()) parts.push_back({"application/simple-filter+xml", mcdata_client_filter()});
    const auto [type, body] = message_body(parts, "mcdata-" + random_id());
    const auto dialog = sip_.subscribe(
        mcdata_service(), "presence", fetch ? 0U : 4294967295U,
        {{"P-Preferred-Service", mcdata::icsi}, {"Accept", "application/pidf+xml"}},
        [this, target](const SipResponse &response) {
          event("mcdata_target_affiliation_subscription", {{"target", target}, {"status", response.status_code}});
        },
        type, body);
    mcdata_extra_subscriptions_[dialog] = target;
    return;
  }
  if (verb == "mcdata-affiliation-unsubscribe") {
    const auto found = std::find_if(mcdata_extra_subscriptions_.begin(), mcdata_extra_subscriptions_.end(),
                                    [&](const auto &entry) { return entry.second == target; });
    if (found == mcdata_extra_subscriptions_.end()) throw std::runtime_error("no affiliation subscription to " + target);
    sip_.request_in_dialog(found->first, "SUBSCRIBE", "", "",
                           {{"Event", "presence"}, {"Accept", "application/pidf+xml"}, {"Expires", "0"}},
                           [this, target](const SipResponse &response) {
                             event("mcdata_target_affiliation_unsubscribed",
                                   {{"target", target}, {"status", response.status_code}});
                           });
    return;
  }
  if (verb != "mcdata-affiliate-remote") throw std::runtime_error("unknown MCData affiliation command");
  std::string group, operation, mode;
  words >> group >> operation >> mode;
  if (group.empty() || (operation != "affiliate" && operation != "deaffiliate") ||
      (mode != "mandatory" && mode != "negotiated"))
    throw std::runtime_error("mcdata-affiliate-remote <MCData ID> <group> affiliate|deaffiliate mandatory|negotiated");
  const bool affiliate = operation == "affiliate";
  mcdata::Info info;
  info.request_uri = target;
  info.client_id = client_id();
  if (mode == "negotiated") {
    // Clause 8.2.4: a MESSAGE to the participating function: 1) its PSI, 2) the mcdata-info naming the target, 3) the
    // MCData ICSI, 4) the affiliation command (annex D.3).
    std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
    parts.push_back({mcdata_affiliation_command_mime,
                     mcdata_command_xml(affiliate ? std::vector{group} : std::vector<std::string>{},
                                        affiliate ? std::vector<std::string>{} : std::vector{group})});
    mcdata_request("", parts,
                   {{"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcdata::icsi) + "\";require;explicit"}},
                   [this, target](const SipResponse &response) {
                     event("mcdata_affiliation_request_delivered", {{"target", target}, {"status", response.status_code}});
                   },
                   "MESSAGE");
    return;
  }
  // Clause 8.2.2 in mandatory mode: a PUBLISH for the targeted user's client, its groups changed. The client ID and
  // the groups come from the targeted user's affiliation notification (mcdata-affiliation-subscribe first).
  const auto known = mcdata_target_affiliations_.find(target);
  if (known == mcdata_target_affiliations_.end() || known->second.client_id.empty())
    throw std::runtime_error("the affiliation of " + target + " is not known: mcdata-affiliation-subscribe first");
  auto affiliation = Affiliation{target, known->second.client_id, random_id(), known->second.groups, {}};
  affiliation.mcdata = true;
  auto &groups = affiliation.groups;
  groups.erase(std::remove(groups.begin(), groups.end(), group), groups.end());
  if (affiliate) groups.push_back(group);
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  // Steps 4 and 5: Expires 0 when the targeted user keeps no group, else 4294967295 with the PIDF.
  if (!groups.empty()) parts.push_back({"application/pidf+xml", affiliation_xml(affiliation, false)});
  mcdata_request("presence", parts, {{"Expires", groups.empty() ? "0" : mcdata_forever}},
                 [this, target, operation](const SipResponse &response) {
                   event("mcdata_remote_affiliation",
                         {{"target", target}, {"operation", operation}, {"status", response.status_code}});
                 });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.4.2.2: the per-client simple-filter of this client's affiliation status subscription: the tuple
// whose "id" is the MCData client ID.
std::string Client::mcdata_client_filter() const {
  return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><filter-set xmlns=\"urn:ietf:params:xml:ns:simple-filter\">"
         "<ns-bindings><ns-binding prefix=\"pidf\" urn=\"urn:ietf:params:xml:ns:pidf\"/>"
         "<ns-binding prefix=\"mcdataPI10\" urn=\"urn:3gpp:ns:mcdataPresInfo:1.0\"/></ns-bindings>"
         "<filter id=\"" +
         random_id().substr(0, 8) + "\"><what><include>//pidf:presence/pidf:tuple[@id=\"" + xml_escape(client_id()) +
         "\"]</include></what></filter></filter-set>";
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.7: the subscription to a group's dynamic data: an initial SUBSCRIBE (event package presence,
// RFC 3856) to the participating function's PSI (1)), with the mcdata-info naming the group (2)), the MCData ICSI
// (3)), Expires 4294967295 (4)) or 0 to fetch the current state (5)), Accept application/pidf+xml (6)) and a
// simple-filter body "indicating the MCData group ID" (7)); in the dialog Expires 4294967295 (re-subscribe) or 0
// (de-subscribe). Clause 7) points to clause 8.4.2, which defines no per-group dynamic data filter: the client sends
// the per-group filter of TS 24.281 clause 8.3.2.2 in the MCData namespace.
//   mcdata-gdd-subscribe <group> [fetch] [status] [broadcast] [regroup] [call] [affiliated]
//   mcdata-gdd-resubscribe <group>   mcdata-gdd-unsubscribe <group>
void Client::mcdata_gdd_command(const std::string &verb, std::istringstream &words) {
  if (!mcdata_authorized_) throw std::runtime_error("MCData group dynamic data requires authorisation");
  std::string group;
  words >> group;
  if (group.empty()) throw std::runtime_error(verb + " <group>");
  if (verb == "mcdata-gdd-subscribe") {
    if (mcdata_gdd_dialogs_.contains(group)) throw std::runtime_error("already subscribed to " + group);
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
      else throw std::runtime_error("mcdata-gdd-subscribe <group> [fetch] [status] [broadcast] [regroup] [call] [affiliated]");
    }
    auto filter_xml = group_dynamic_data_filter_xml(filter);
    for (const auto &[from, to] : {std::pair<std::string, std::string>{"mcvideoPresInfo", "mcdataPresInfo"},
                                   {"mcvideoPI10", "mcdataPI10"}})
      for (std::size_t at; (at = filter_xml.find(from)) != std::string::npos;) filter_xml.replace(at, from.size(), to);
    mcdata::Info info;
    info.request_uri = group; // step 2
    info.client_id = client_id();
    std::vector<BodyPart> parts = mcdata_encrypt_xml({{mcdata::info_mime, mcdata::info_xml(info)}}, mcdata_xml_key());
    parts.push_back({"application/simple-filter+xml", filter_xml}); // step 7
    const auto [type, body] = message_body(parts, "mcdata-" + random_id());
    const auto dialog = sip_.subscribe(
        mcdata_service(), "presence", fetch ? 0U : 4294967295U,
        {{"P-Preferred-Service", mcdata::icsi}, {"Accept", "application/pidf+xml"}},
        [this, group](const SipResponse &response) {
          if (response.status_code < 200) return;
          mcdata_gdd_[group]["subscription_status"] = response.status_code;
          if (response.status_code >= 300) mcdata_gdd_dialogs_.erase(group);
          event("mcdata_gdd_subscription", {{"group", group}, {"status", response.status_code}});
        },
        type, body);
    mcdata_extra_subscriptions_[dialog] = "gdd:" + group;
    if (!fetch) mcdata_gdd_dialogs_[group] = dialog;
    return;
  }
  const auto found = mcdata_gdd_dialogs_.find(group);
  if (found == mcdata_gdd_dialogs_.end()) throw std::runtime_error("no group dynamic data subscription to " + group);
  const bool resubscribe = verb == "mcdata-gdd-resubscribe";
  if (!resubscribe && verb != "mcdata-gdd-unsubscribe") throw std::runtime_error("unknown group dynamic data command");
  sip_.request_in_dialog(found->second, "SUBSCRIBE", "", "",
                         {{"Event", "presence"},
                          {"Accept", "application/pidf+xml"},
                          {"Expires", resubscribe ? mcdata_forever : "0"}},
                         [this, group, resubscribe](const SipResponse &response) {
                           event(resubscribe ? "mcdata_gdd_resubscribed" : "mcdata_gdd_unsubscribed",
                                 {{"group", group}, {"status", response.status_code}});
                         });
  if (!resubscribe) mcdata_gdd_dialogs_.erase(found);
}

//------------------------------------------------------------------------------
// RFC 6665 section 4.1.3: the NOTIFYs of the subscriptions above: poc-settings (clause 7.2.4), group dynamic data
// (clause 8.2.7: the notifications counted) and another user's affiliation (clause 8.2.3), whose client ID and groups
// are kept for a mandatory-mode change.
SipResponse Client::mcdata_extra_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto found = mcdata_extra_subscriptions_.find(message.dialog_id);
  if (found == mcdata_extra_subscriptions_.end()) {
    response.status_code = 481;
    return response;
  }
  const auto subject = found->second;
  const bool terminated = get_header(message.headers, "Subscription-State").starts_with("terminated");
  if (terminated) mcdata_extra_subscriptions_.erase(found);
  if (subject == "poc-settings") return mcdata_settings_notify(message);
  if (subject.starts_with("gdd:")) {
    const auto group = subject.substr(4);
    if (terminated) mcdata_gdd_dialogs_.erase(group);
    auto &entry = mcdata_gdd_[group];
    entry["notifications"] = entry.value("notifications", 0) + 1;
    entry["body"] = !mcdata_part(message, "application/pidf+xml").empty();
    event("mcdata_gdd_notified", {{"group", group}});
    return response;
  }
  const auto body = mcdata_part(message, "application/pidf+xml");
  if (body.empty()) return response;
  try {
    auto affiliation = parse_affiliation(body);
    // Clause 8.2.2 5) c): a later mandatory-mode request carries the groups the targeted user is interested in at its
    // client: those notified "deaffiliating" or "deaffiliated" (clause 8.4.1) are not.
    std::vector<std::string> groups;
    for (std::size_t index = 0; index < affiliation.groups.size(); ++index)
      if (client_rules::counts_as_affiliated(index < affiliation.statuses.size() ? affiliation.statuses[index] : ""))
        groups.push_back(affiliation.groups[index]);
    affiliation.groups = groups;
    affiliation.statuses.clear();
    mcdata_target_affiliations_[subject] = affiliation;
    event("mcdata_target_affiliation", {{"target", subject}, {"groups", affiliation.groups}});
  } catch (const std::exception &error) {
    event("mcdata_target_affiliation_unread", {{"target", subject}, {"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.5: a negotiated-mode affiliation status change request (a MESSAGE with an
// application/vnd.3gpp.mcdata-affiliation-command+xml body): 200 (OK) at once (1)), then the user's confirmation (2));
// the groups are kept until mcdata-affiliation-accept or -decline. Empty when the message carries no such body.
std::optional<SipResponse> Client::mcdata_affiliation_command_message(const SipMessage &message,
                                                                      const std::vector<BodyPart> &parts) {
  std::string body;
  for (const auto &part : parts)
    if (part.content_type == mcdata_affiliation_command_mime) body = part.body;
  if (body.empty()) return std::nullopt;
  SipResponse response;
  response.status_code = 200;
  mcdata_affiliation_command_ = {{"affiliate", mcdata_command_groups(body, "affiliate")},
                                 {"de-affiliate", mcdata_command_groups(body, "de-affiliate")},
                                 {"from", message.from_uri}};
  event("mcdata_affiliation_command_received", mcdata_affiliation_command_);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.5 steps 2 and 3: the user's confirmation: accepted (only the groups named, when some are),
// a) affiliation to each group of the accepted affiliation list not affiliated and b) de-affiliation from each group of
// the accepted de-affiliation list affiliated, as in clause 8.2.2; no other group changes.
void Client::mcdata_affiliation_command_answer(bool accept, const std::set<std::string> &only) {
  if (!mcdata_affiliation_command_.is_object()) throw std::runtime_error("no MCData affiliation request");
  const auto command = std::exchange(mcdata_affiliation_command_, Json());
  event(accept ? "mcdata_affiliation_command_accepted" : "mcdata_affiliation_command_declined");
  if (!accept) return;
  const auto accepted = [&only](std::vector<std::string> groups) {
    if (!only.empty()) std::erase_if(groups, [&only](const std::string &group) { return !only.contains(group); });
    return groups;
  };
  mcdata_change_affiliation(accepted(command.value("affiliate", std::vector<std::string>{})),
                            accepted(command.value("de-affiliate", std::vector<std::string>{})));
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.2: the groups the user is interested in at this client: the configured group while the user
// wants it, the groups the server reports affiliated (e.g. implicit affiliations) and those the user joined, without
// the ones the user left.
std::vector<std::string> Client::mcdata_interest(const std::vector<std::string> &added) const {
  std::vector<std::string> out;
  const auto add = [&out, this](const std::string &group) {
    if (!group.empty() && std::find(out.begin(), out.end(), group) == out.end() &&
        (!mcdata_left_groups_.contains(group)))
      out.push_back(group);
  };
  if (mcdata_desired_affiliation_) add(mcdata_group());
  for (const auto &group : mcdata_groups_) add(group);
  for (const auto &group : mcdata_extra_groups_) add(group);
  for (const auto &group : added)
    if (!group.empty() && std::find(out.begin(), out.end(), group) == out.end()) out.push_back(group);
  return out;
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 8.2.2 and 8.2.5 3): one PUBLISH with the affiliation changed: a) the groups of `affiliate` the
// client is not affiliated to join it, b) the groups of `leave` it is affiliated to leave it; the other groups stay
// (5) c): the request names all the groups the user is interested in). No group left: Expires 0 (4)).
bool Client::mcdata_change_affiliation(const std::vector<std::string> &affiliate, const std::vector<std::string> &leave) {
  if (!mcdata_authorized_) throw std::runtime_error("MCData affiliation requires MCData service authorisation");
  const auto current = mcdata_interest({});
  const auto has = [&current](const std::string &group) {
    return std::find(current.begin(), current.end(), group) != current.end();
  };
  std::vector<std::string> added, removed;
  for (const auto &group : affiliate)
    if (!has(group) && std::find(added.begin(), added.end(), group) == added.end()) added.push_back(group);
  for (const auto &group : leave)
    if (has(group) && std::find(removed.begin(), removed.end(), group) == removed.end()) removed.push_back(group);
  event("mcdata_affiliation_change", {{"affiliate", added}, {"de-affiliate", removed}});
  if (added.empty() && removed.empty()) return false;
  for (const auto &group : removed) {
    mcdata_left_groups_.insert(group);
    mcdata_extra_groups_.erase(group);
  }
  for (const auto &group : added) {
    mcdata_left_groups_.erase(group);
    if (group == mcdata_group()) mcdata_desired_affiliation_ = true;
  }
  if (mcdata_interest(added).empty()) {
    mcdata_publish_affiliation({}, true);
    return true;
  }
  mcdata_publish_affiliation(added);
  return true;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.2: the PUBLISH itself, for the groups of mcdata_interest(added): 1) the participating
// function's PSI, 2) the mcdata-info with <mcdata-request-uri> this user's MCData ID, 3) the MCData ICSI; with no
// group, 4) Expires 0 and no PIDF; else 5) a) Expires 4294967295, b) the PIDF of clause 8.4.1 with c) the groups,
// d) this client's ID, e) no "status" or "expires", f) a new <p-id>. The groups in `added` join the user's groups once
// the server accepts them. none: no group at all (the user is no longer interested in any MCData group).
void Client::mcdata_publish_affiliation(const std::vector<std::string> &added, bool none) {
  mcdata::Info info;
  info.request_uri = mcdata_user();
  info.client_id = client_id();
  Affiliation affiliation{mcdata_user(), client_id(), random_id(),
                          none ? std::vector<std::string>{} : mcdata_interest(added), {}, true};
  const bool affiliate = !affiliation.groups.empty();
  mcdata_sent_pid_ = affiliation.publication_id;
  std::vector<SipHeader> headers{{"Expires", affiliate ? mcdata_forever : "0"}};
  const bool refresh = !mcdata_affiliation_etag_.empty();
  if (refresh) headers.push_back({"SIP-If-Match", mcdata_affiliation_etag_});
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (affiliate) parts.push_back({"application/pidf+xml", affiliation_xml(affiliation, false)});
  mcdata_request("presence", parts, headers, [this, affiliate, added, refresh, none](const SipResponse &response) {
    if (response.status_code != 200) {
      failure("MCData affiliation: " + std::to_string(response.status_code));
      if (refresh && response.status_code == 412) {
        mcdata_affiliation_etag_.clear();
        schedule([this, added, none] { mcdata_publish_affiliation(added, none); });
      }
      return;
    }
    mcdata_affiliation_etag_ = affiliate ? std::string(get_header(response.headers, "SIP-ETag")) : std::string{};
    for (const auto &group : added)
      if (group != mcdata_group()) mcdata_extra_groups_.insert(group);
    event(affiliate ? "mcdata_affiliation_requested" : "mcdata_deaffiliation_requested", {{"groups", added}});
  });
}

} // namespace mcx
