//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The service settings and the affiliation procedures towards other users, one implementation for MCPTT and MCVideo
// (the procedures are the same in both specifications, with the service's ICSI, info body, PSI and IDs):
//   - the settings-only PUBLISH (TS 24.379 clause 7.2.3, TS 24.281 clause 7.2.3) and the determination of the service
//     settings (clause 7.2.4 of both);
//   - the affiliation status determination of another user (TS 24.379 clause 9.2.1.3, TS 24.281 clause 8.2.1.3), the
//     affiliation status change for another user in mandatory mode (clauses 9.2.1.2 / 8.2.1.2), and in negotiated mode
//     sending (clauses 9.2.1.4 / 8.2.1.4) and receiving (clauses 9.2.1.5 / 8.2.1.5);
//   - the subscription to group dynamic data (TS 24.379 clause 9.2.1.6, TS 24.281 clause 8.2.1.6).
// The procedures of a service run on its ServiceProcedures; its commands are the verbs below, prefixed "mcvideo-" for
// MCVideo and bare for MCPTT, and its status keys and event names are prefixed "mcvideo_" for MCVideo (service_key).
// MCData has its own copy (mcdata/settings_affiliation.cpp): its info body (TS 24.282 annex D.1) is not the
// <mcpttinfo> of the other two.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), IETF RFC 6665, RFC 4354, RFC 3856,
// RFC 3903, RFC 3428, RFC 4661.

#include "client.hpp"

namespace mcx {

namespace {
constexpr const char *forever = "4294967295";

// TS 24.379 annex F.4 (TS 24.281 annex F.4): the MIME type of the affiliation command of the service:
// application/vnd.3gpp.mcptt-affiliation-command+xml or application/vnd.3gpp.mcvideo-affiliation-command+xml.
std::string affiliation_command_mime(const ServiceProfile &service) {
  return std::string("application/vnd.3gpp.") + service.info_prefix + "-affiliation-command+xml";
}

// TS 24.379 annex F.4 (TS 24.281 annex F.4): a <command-list> with the groups to affiliate to or de-affiliate from.
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

// The body of the given type of a message, single or one of its parts; empty when there is none.
std::string part_of(const SipMessage &message, const std::string &type) {
  std::string body;
  if (message.parts.empty()) {
    if (message.content_type.starts_with(type)) body = message.body;
  } else {
    for (const auto &part : message.parts)
      if (part.content_type.starts_with(type)) body = part.body;
  }
  return body;
}
} // namespace

//------------------------------------------------------------------------------
// Helpers: what tells the services apart in these procedures.
Client::ServiceProcedures &Client::procedures(const ServiceProfile &service) {
  return service.service == Service::mcvideo ? mcvideo_procedures_ : mcptt_procedures_;
}

// The status key or event name of the service: "mcvideo_<name>" for MCVideo, "<name>" for MCPTT.
std::string Client::service_key(const ServiceProfile &service, const std::string &name) const {
  return service.service == Service::mcvideo ? "mcvideo_" + name : name;
}

// The command verb of the service: "mcvideo-<verb>" for MCVideo, "<verb>" for MCPTT.
std::string Client::service_verb(const ServiceProfile &service, const std::string &verb) const {
  return service.service == Service::mcvideo ? "mcvideo-" + verb : verb;
}

// The MC service ID of this user: the MCPTT ID or the MCVideo ID.
std::string Client::service_user(const ServiceProfile &service) const {
  return service.service == Service::mcvideo ? mcvideo_user() : user();
}

// TS 24.379 clause 7.2.1A 1) (TS 24.281 clause 7.2.1A 1)): the PSI of the participating function serving the user.
std::string Client::service_psi(const ServiceProfile &service) const {
  return service.service == Service::mcvideo ? mcvideo_service_uri() : this->service();
}

bool Client::service_authorized(const ServiceProfile &service) const {
  return service.service == Service::mcvideo ? mcvideo_authorized_ : authorized_;
}

// The poc-settings of this client for the service (TS 24.379 clause 7.2.3 4), TS 24.281 clause 7.2.3 4)).
std::string Client::service_poc_settings(const ServiceProfile &service) {
  return service.service == Service::mcvideo ? mcvideo_poc_settings() : mcptt_poc_settings();
}

// RFC 3903: the entity-tag of the service's poc-settings publication (that of the service authorisation PUBLISH of
// clause 7.2.2, which carries the settings too).
std::string &Client::settings_etag(const ServiceProfile &service) {
  return service.service == Service::mcvideo ? mcvideo_auth_etag_ : auth_etag_;
}

// TS 24.379 clause 9.2.1.5 3) (TS 24.281 clause 8.2.1.5 3)): affiliation and de-affiliation as in clause 9.2.1.2
// (8.2.1.2) for this client.
bool Client::change_affiliation(const ServiceProfile &service, const std::vector<std::string> &affiliate,
                                const std::vector<std::string> &leave) {
  if (service.service != Service::mcvideo) throw std::runtime_error("MCPTT affiliation change not supported");
  return mcvideo_change_affiliation(affiliate, leave);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.3.2.2 (TS 24.281 clause 8.3.2.2, RFC 4661): the simple-filter restricting presence notifications
// to this client: the "pidf" and the service's presence bindings ("mcpttPI10", "mcvideoPI10"), one <filter> (an id, no
// uri or domain) including this client's tuple (item 7 B: //pidf:presence/pidf:tuple[@id="<client ID>"]).
std::string Client::client_filter_xml(const ServiceProfile &service) const {
  const bool video = service.service == Service::mcvideo;
  return std::string("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                     "<filter-set xmlns=\"urn:ietf:params:xml:ns:simple-filter\"><ns-bindings>"
                     "<ns-binding prefix=\"pidf\" urn=\"urn:ietf:params:xml:ns:pidf\"/>") +
         (video ? "<ns-binding prefix=\"mcvideoPI10\" urn=\"urn:3gpp:ns:mcvideoPresInfo:1.0\"/>"
                : "<ns-binding prefix=\"mcpttPI10\" urn=\"urn:3gpp:ns:mcpttPresInfo:1.0\"/>") +
         "</ns-bindings><filter id=\"" + random_id().substr(0, 12) +
         "\"><what><include>//pidf:presence/pidf:tuple[@id=&quot;" + xml_escape(client_id()) +
         "&quot;]</include></what></filter></filter-set>";
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.1A steps 1) and 2) (TS 24.281 clause 7.2.1A), and clause 9.2.1.2 steps 1) and 3): a PUBLISH to
// the participating function's PSI with the service's ICSI in P-Preferred-Service and the given Event.
void Client::service_publish(const ServiceProfile &service, const std::string &event_name,
                             const std::vector<BodyPart> &parts, std::vector<SipHeader> headers,
                             std::function<void(const SipResponse &)> done) {
  headers.push_back({"P-Preferred-Service", service.icsi});
  headers.push_back({"Event", event_name});
  const auto [type, body] = message_body(parts, std::string(service.name) + "-" + random_id());
  sip_.request("PUBLISH", service_psi(service), type, body, headers, [done](const SipResponse &response) {
    if (response.status_code >= 200) done(response);
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.3 (TS 24.281 clause 7.2.3): settings-publish [remove]: the SIP PUBLISH for the service settings
// only, to set, update or refresh them (or, with remove, to remove them):
//   1) clause 7.2.1A: Request-URI the participating function's PSI, P-Preferred-Service, Event poc-settings, Expires
//      4294967295 (0 to remove the settings, which logs the client off the service, NOTE 3);
//   2), 3) the info body with <mcptt-request-uri> (<mcvideo-request-uri>), this user's ID (the targeted one), and
//      <mcptt-client-id> (<mcvideo-client-id>); their confidentiality protection, when enabled (clause 6.6.2.3.1),
//      and 5) the integrity protection of both bodies (clause 6.6.3.3.1) are those of every request of the service
//      (protect_outgoing);
//   4) the application/poc-settings+xml body (service_poc_settings): the current answer mode, the selected user
//      profile's "user-profile-index" and <multiplex-support>.
// On the 200 (OK) the user may be told the settings reached the server; on a 3xx to 5xx, told they did not, with the
// Warning, and the settings are taken as not set (settings_publication). RFC 3903: the publication of the service
// settings is modified with the entity-tag of the last one, refreshed with the new one.
void Client::settings_publish(const ServiceProfile &service, bool remove) {
  if (!service_authorized(service)) throw std::runtime_error(std::string(service.label) + " service settings require authorisation");
  McpttInfo info;
  info.request_uri = service_user(service);
  info.client_id = client_id();
  std::vector<SipHeader> headers{{"Expires", remove ? "0" : forever}};
  const bool modify = !settings_etag(service).empty();
  if (modify) headers.push_back({"SIP-If-Match", settings_etag(service)});
  auto parts = info_parts(info, service);
  parts.push_back({"application/poc-settings+xml", service_poc_settings(service)});
  service_publish(service, "poc-settings", parts, headers, [this, &service, remove, modify](const SipResponse &response) {
    auto &state = procedures(service);
    state.settings_result = {{"action", remove ? "remove" : "publish"},
                             {"status", response.status_code},
                             {"warning", std::string(get_header(response.headers, "Warning"))}};
    status_[service_key(service, "settings_publication")] = state.settings_result;
    if (response.status_code != 200) {
      // RFC 3903 section 5: after a 412 to a modification, discard the entity-tag and publish anew.
      if (modify && response.status_code == 412 && !remove) {
        settings_etag(service).clear();
        schedule([this, &service] {
          if (service_authorized(service)) settings_publish(service, false);
        });
      }
      event(service_key(service, "settings_publication_refused"), {{"status", response.status_code}});
      return;
    }
    if (remove) {
      // Clause 7.2.1A NOTE 3 (clause 7.3.5 at the server): the removal of the settings logs the client off the
      // service, its group affiliations removed with them.
      settings_etag(service).clear();
      if (service.service == Service::mcvideo) {
        mcvideo_affiliation_etag_.clear();
        mcvideo_authorized_ = mcvideo_affiliated_ = false;
        mcvideo_groups_.clear();
      } else {
        affiliation_etag_.clear();
        authorized_ = affiliated_ = false;
        affiliation_statuses_ = Json::object();
      }
      event(service_key(service, "settings_removed"));
      return;
    }
    if (const auto etag = get_header(response.headers, "SIP-ETag"); !etag.empty()) settings_etag(service) = etag;
    event(service_key(service, "settings_published"));
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.4 (TS 24.281 clause 7.2.4): a NOTIFY of the poc-settings subscription with an
// application/poc-settings+xml body: for each client the body names by the "id" of its <entity> (RFC 4354), the client
// caches 1) its <am-settings> as the current Answer-mode indication of that client and 2) its
// <selected-user-profile-index> as the active user profile of that client. A NOTIFY without the body (e.g. the final
// one) changes nothing.
SipResponse Client::settings_notify(const ServiceProfile &service, const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto body = part_of(message, "application/poc-settings+xml");
  if (body.empty()) {
    event(service_key(service, "settings_notified"), {{"body", false}});
    return response;
  }
  auto &state = procedures(service);
  try {
    const auto entities = parse_poc_settings(body);
    for (const auto &entity : entities) {
      auto &cached = state.service_settings[entity.id];
      if (!cached.is_object()) cached = Json::object();
      // RFC 4354: <answer-mode> "automatic" or "manual"; kept as notified.
      if (!entity.answer_mode.empty()) cached["answer_mode"] = entity.answer_mode;
      if (entity.profile_index) cached["profile_index"] = *entity.profile_index;
      if (entity.multiplex_support) cached["multiplex_support"] = *entity.multiplex_support;
      cached["own"] = entity.id == client_id();
    }
    status_[service_key(service, "service_settings")] = state.service_settings;
    event(service_key(service, "settings_notified"), {{"body", true}, {"clients", entities.size()}});
  } catch (const std::exception &error) {
    // An unreadable document leaves the cache as it was (RFC 6665: the NOTIFY is still answered).
    event(service_key(service, "settings_unread"), {{"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.4 (TS 24.281 clause 7.2.4): settings-publish [remove] (clause 7.2.3), and settings-subscribe
// [fetch], settings-resubscribe, settings-unsubscribe: the SUBSCRIBE to this user's service settings (event package
// poc-settings).
void Client::settings_command(const ServiceProfile &service, const std::string &verb, std::istringstream &words) {
  if (!service_authorized(service))
    throw std::runtime_error(std::string(service.label) + " service settings require authorisation");
  auto &state = procedures(service);
  std::string option;
  words >> option;
  if (verb == "settings-publish") {
    if (!option.empty() && option != "remove") throw std::runtime_error(service_verb(service, verb) + " [remove]");
    return settings_publish(service, option == "remove");
  }
  if (verb == "settings-subscribe") {
    if (!option.empty() && option != "fetch") throw std::runtime_error(service_verb(service, verb) + " [fetch]");
    McpttInfo info; // step 2: <mcptt-request-uri> set to this user's ID
    info.request_uri = service_user(service);
    info.client_id = client_id();
    const bool fetch = option == "fetch"; // step 7: Expires 0 fetches the current state only, else step 6
    const auto parts = info_parts(info, service);
    const auto dialog = sip_.subscribe(
        service_psi(service), "poc-settings", fetch ? 0U : 4294967295U,                    // steps 1, 4
        {{"P-Preferred-Service", service.icsi}, {"Accept", "application/poc-settings+xml"}}, // steps 3, 5
        [this, &service](const SipResponse &response) {
          event(service_key(service, "settings_subscription"), {{"status", response.status_code}});
        },
        parts.front().content_type, parts.front().body);
    state.settings_subscription = dialog;
    state.extra_subscriptions[dialog] = "poc-settings";
    return;
  }
  const bool resubscribe = verb == "settings-resubscribe";
  if (!resubscribe && verb != "settings-unsubscribe")
    throw std::runtime_error("unknown " + std::string(service.label) + " settings command");
  if (state.settings_subscription.empty())
    throw std::runtime_error("no " + std::string(service.label) + " settings subscription");
  // In-dialog: 1) Event poc-settings, 2) Accept, 3) Expires 4294967295 (re-subscribe) or 4) 0 (de-subscribe).
  sip_.request_in_dialog(state.settings_subscription, "SUBSCRIBE", "", "",
                         {{"Event", "poc-settings"},
                          {"Accept", "application/poc-settings+xml"},
                          {"Expires", resubscribe ? forever : "0"}},
                         [this, &service, resubscribe](const SipResponse &response) {
                           event(service_key(service, resubscribe ? "settings_resubscribed" : "settings_unsubscribed"),
                                 {{"status", response.status_code}});
                         });
  if (!resubscribe) state.settings_subscription.clear();
}

//------------------------------------------------------------------------------
// TS 24.379 clauses 9.2.1.3, 9.2.1.2 and 9.2.1.4 (TS 24.281 clauses 8.2.1.3, 8.2.1.2, 8.2.1.4): the affiliation of
// another user (or of this one, with a subscription of its own beside the client's):
//   affiliation-subscribe <ID> [fetch]   affiliation-unsubscribe <ID>
//   affiliate-remote <ID> <group> affiliate|deaffiliate mandatory|negotiated
//   affiliation-accept [<group>...]   affiliation-decline   (a received negotiated-mode request, clause 9.2.1.5)
void Client::target_affiliation_command(const ServiceProfile &service, const std::string &verb,
                                        std::istringstream &words) {
  if (!service_authorized(service))
    throw std::runtime_error(std::string(service.label) + " affiliation requires authorisation");
  auto &state = procedures(service);
  if (verb == "affiliation-accept" || verb == "affiliation-decline") {
    std::set<std::string> only;
    for (std::string group; words >> group;) only.insert(group);
    return affiliation_command_answer(service, verb == "affiliation-accept", only);
  }
  std::string target;
  words >> target;
  if (target.empty()) throw std::runtime_error(service_verb(service, verb) + " needs an " + service.label + " ID");
  if (verb == "affiliation-subscribe") {
    std::string option;
    words >> option;
    if (!option.empty() && option != "fetch") throw std::runtime_error(service_verb(service, verb) + " <ID> [fetch]");
    McpttInfo info; // step 2: <mcptt-request-uri> set to the targeted user's ID
    info.request_uri = target;
    info.client_id = client_id();
    const bool fetch = option == "fetch"; // step 5, else step 4
    auto parts = info_parts(info, service);
    // Step 7: for this user's groups at this client, the per-client simple-filter (clause 9.3.2).
    if (target == service_user(service)) parts.push_back({"application/simple-filter+xml", client_filter_xml(service)});
    const auto [type, body] = message_body(parts, std::string(service.name) + "-" + random_id());
    const auto dialog = sip_.subscribe(
        service_psi(service), "presence", fetch ? 0U : 4294967295U,                // step 1
        {{"P-Preferred-Service", service.icsi}, {"Accept", "application/pidf+xml"}}, // steps 3, 6
        [this, &service, target](const SipResponse &response) {
          event(service_key(service, "target_affiliation_subscription"),
                {{"target", target}, {"status", response.status_code}});
        },
        type, body);
    state.extra_subscriptions[dialog] = target;
    return;
  }
  if (verb == "affiliation-unsubscribe") {
    const auto found = std::find_if(state.extra_subscriptions.begin(), state.extra_subscriptions.end(),
                                    [&](const auto &entry) { return entry.second == target; });
    if (found == state.extra_subscriptions.end()) throw std::runtime_error("no affiliation subscription to " + target);
    // In-dialog: 2) Expires 0 and 3) Accept application/pidf+xml.
    sip_.request_in_dialog(found->first, "SUBSCRIBE", "", "",
                           {{"Event", "presence"}, {"Accept", "application/pidf+xml"}, {"Expires", "0"}},
                           [this, &service, target](const SipResponse &response) {
                             event(service_key(service, "target_affiliation_unsubscribed"),
                                   {{"target", target}, {"status", response.status_code}});
                           });
    return;
  }
  if (verb != "affiliate-remote") throw std::runtime_error("unknown " + std::string(service.label) + " affiliation command");
  std::string group, operation, mode;
  words >> group >> operation >> mode;
  if (group.empty() || (operation != "affiliate" && operation != "deaffiliate") ||
      (mode != "mandatory" && mode != "negotiated"))
    throw std::runtime_error(service_verb(service, verb) +
                             " <ID> <group> affiliate|deaffiliate mandatory|negotiated");
  const bool affiliate = operation == "affiliate";
  McpttInfo info;
  info.request_uri = target;
  info.client_id = client_id();
  if (mode == "negotiated") {
    // Clause 9.2.1.4: a MESSAGE (RFC 3428) to the PSI (step 1) with the info body naming the target user (step 2),
    // P-Preferred-Service (step 3) and the affiliation command of annex F.4 (step 4).
    auto parts = info_parts(info, service);
    parts.push_back({affiliation_command_mime(service),
                     affiliation_command_xml(affiliate ? std::vector{group} : std::vector<std::string>{},
                                             affiliate ? std::vector<std::string>{} : std::vector{group})});
    const auto [type, body] = message_body(parts, std::string(service.name) + "-" + random_id());
    // MCVideo: also the Accept-Contact of its calls (mcvideo_call_headers); TS 24.379 asks P-Preferred-Service only.
    const auto headers = service.service == Service::mcvideo ? mcvideo_call_headers()
                                                             : std::vector<SipHeader>{{"P-Preferred-Service", service.icsi}};
    sip_.request("MESSAGE", service_psi(service), type, body, headers,
                 [this, &service, target](const SipResponse &response) {
                   // On a 2xx: the request was delivered to a client of the target user.
                   event(service_key(service, "affiliation_request_delivered"),
                         {{"target", target}, {"status", response.status_code}});
                 });
    return;
  }
  // Clause 9.2.1.2 in mandatory mode: a PUBLISH for the targeted user's client, its groups changed. The client ID and
  // the groups come from the targeted user's affiliation notification (affiliation-subscribe first).
  const auto known = state.target_affiliations.find(target);
  if (known == state.target_affiliations.end() || known->second.client_id.empty())
    throw std::runtime_error("the affiliation of " + target + " is not known: " +
                             service_verb(service, "affiliation-subscribe") + " first");
  // Steps 5 d) and f): the targeted client's ID and a new p-id; e) no "status" or "expires" (affiliation_xml(..., false)).
  auto affiliation = Affiliation{target, known->second.client_id, random_id(), known->second.groups, {}};
  affiliation.mcvideo = service.service == Service::mcvideo;
  auto &groups = affiliation.groups;
  groups.erase(std::remove(groups.begin(), groups.end(), group), groups.end());
  if (affiliate) groups.push_back(group); // step 5 c): all the groups of the targeted user's interest
  auto parts = info_parts(info, service);
  // Steps 4 and 5: Expires 0 when the targeted user keeps no group, else 4294967295 with the PIDF.
  if (!groups.empty()) parts.push_back({"application/pidf+xml", affiliation_xml(affiliation, false)});
  service_publish(service, "presence", parts, {{"Expires", groups.empty() ? "0" : forever}},
                  [this, &service, target, operation](const SipResponse &response) {
                    event(service_key(service, "remote_affiliation"),
                          {{"target", target}, {"operation", operation}, {"status", response.status_code}});
                  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.6 (TS 24.281 clause 8.2.1.6): the subscription to a group's dynamic data. The initial
// SUBSCRIBE (event package presence, RFC 3856): to the participating function's PSI (step 1), with the info body naming
// the group (step 2), P-Preferred-Service (step 3), Expires 4294967295 (step 4) or 0 to fetch the current state only
// (step 5), Accept application/pidf+xml (step 6) and the per-group dynamic data simple-filter of clause 9.3.2.2
// naming the group and the items asked for (step 7; none asks for all). In the dialog: Expires 4294967295
// (re-subscribe, step 1) or 0 (de-subscribe, step 2), with the Accept (step 3).
//   gdd-subscribe <group> [fetch] [status] [broadcast] [regroup] [call] [affiliated]
//   gdd-resubscribe <group>   gdd-unsubscribe <group>
void Client::gdd_command(const ServiceProfile &service, const std::string &verb, std::istringstream &words) {
  if (!service_authorized(service))
    throw std::runtime_error(std::string(service.label) + " group dynamic data requires authorisation");
  auto &state = procedures(service);
  std::string group;
  words >> group;
  if (group.empty()) throw std::runtime_error(service_verb(service, verb) + " <group>");
  if (verb == "gdd-subscribe") {
    if (state.gdd_dialogs.contains(group)) throw std::runtime_error("already subscribed to " + group);
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
      else
        throw std::runtime_error(service_verb(service, verb) +
                                 " <group> [fetch] [status] [broadcast] [regroup] [call] [affiliated]");
    }
    McpttInfo info;
    info.request_uri = group; // step 2
    info.client_id = client_id();
    auto parts = info_parts(info, service);
    parts.push_back({"application/simple-filter+xml", group_dynamic_data_filter_xml(filter, service.service)}); // step 7
    const auto [type, body] = message_body(parts, std::string(service.name) + "-" + random_id());
    const auto dialog = sip_.subscribe(
        service_psi(service), "presence", fetch ? 0U : 4294967295U,                // steps 1, 4, 5
        {{"P-Preferred-Service", service.icsi}, {"Accept", "application/pidf+xml"}}, // steps 3, 6
        [this, &service, group](const SipResponse &response) {
          if (response.status_code < 200) return;
          auto &state = procedures(service);
          state.gdd[group]["subscription_status"] = response.status_code;
          status_[service_key(service, "group_dynamic_data")] = state.gdd;
          if (response.status_code >= 300) state.gdd_dialogs.erase(group);
          event(service_key(service, "gdd_subscription"), {{"group", group}, {"status", response.status_code}});
        },
        type, body);
    state.extra_subscriptions[dialog] = "gdd:" + group;
    if (!fetch) state.gdd_dialogs[group] = dialog;
    return;
  }
  const bool resubscribe = verb == "gdd-resubscribe";
  if (!resubscribe && verb != "gdd-unsubscribe") throw std::runtime_error("unknown group dynamic data command");
  const auto found = state.gdd_dialogs.find(group);
  if (found == state.gdd_dialogs.end()) throw std::runtime_error("no group dynamic data subscription to " + group);
  sip_.request_in_dialog(found->second, "SUBSCRIBE", "", "",
                         {{"Event", "presence"}, {"Accept", "application/pidf+xml"}, {"Expires", resubscribe ? forever : "0"}},
                         [this, &service, group, resubscribe](const SipResponse &response) {
                           event(service_key(service, resubscribe ? "gdd_resubscribed" : "gdd_unsubscribed"),
                                 {{"group", group}, {"status", response.status_code}});
                         });
  if (!resubscribe) state.gdd_dialogs.erase(found);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.6 (TS 24.281 clause 8.2.1.6): a NOTIFY with the application/pidf+xml body of per-group dynamic
// data (clause 9.3.1.2, in the service's presence namespace): the group's status (with ModifiedBy), its broadcast and
// regroup aliases, whether a call is ongoing and its affiliated members are taken as the group's dynamic data.
SipResponse Client::gdd_notify(const ServiceProfile &service, const SipMessage &message, const std::string &group) {
  SipResponse response;
  response.status_code = 200;
  const auto body = part_of(message, "application/pidf+xml");
  if (body.empty()) return response; // RFC 6665: a NOTIFY without state (e.g. a final one)
  auto &state = procedures(service);
  try {
    const auto data = parse_group_dynamic_data(body, service.service);
    if (!data.group.empty() && data.group != group) throw std::runtime_error("dynamic data of another group");
    Json members = Json::array();
    for (const auto &member : data.members) {
      Json clients = Json::array();
      for (const auto &client : member.clients) clients.push_back(client.client);
      members.push_back({{"id", member.id}, {"clients", clients}, {"functional_aliases", member.functional_aliases}});
    }
    auto &entry = state.gdd[group];
    entry["group_status"] = data.group_status;
    entry["modified_by"] = data.modified_by;
    entry["broadcast_alias"] = data.broadcast_alias;
    entry["regroup_alias"] = data.regroup_alias;
    entry["call_ongoing"] = data.call_ongoing ? Json(*data.call_ongoing) : Json();
    entry["members"] = members;
    entry["notifications"] = entry.value("notifications", 0) + 1;
    status_[service_key(service, "group_dynamic_data")] = state.gdd;
    event(service_key(service, "gdd_notified"),
          {{"group", group}, {"group_status", data.group_status}, {"members", members.size()}});
  } catch (const std::exception &error) {
    event(service_key(service, "gdd_unread"), {{"group", group}, {"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// RFC 6665 section 4.1.3: the NOTIFYs of the subscriptions above: poc-settings (clause 7.2.4), group dynamic data
// (TS 24.379 clause 9.2.1.6) and another user's affiliation (clause 9.2.1.3), whose client ID and groups are kept for a
// mandatory-mode change. A NOTIFY of no such subscription: 481.
SipResponse Client::extra_notify(const ServiceProfile &service, const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  auto &state = procedures(service);
  const auto found = state.extra_subscriptions.find(message.dialog_id);
  if (found == state.extra_subscriptions.end()) {
    response.status_code = 481;
    return response;
  }
  const auto subject = found->second;
  const bool terminated = get_header(message.headers, "Subscription-State").starts_with("terminated");
  if (terminated) state.extra_subscriptions.erase(found);
  if (subject == "poc-settings") {
    if (terminated && state.settings_subscription == message.dialog_id) state.settings_subscription.clear();
    return settings_notify(service, message);
  }
  if (subject.starts_with("gdd:")) {
    const auto group = subject.substr(4);
    if (terminated) state.gdd_dialogs.erase(group);
    return gdd_notify(service, message, group);
  }
  const auto body = part_of(message, "application/pidf+xml");
  if (body.empty()) return response;
  try {
    auto affiliation = parse_affiliation(body);
    // Clause 9.2.1.2 5) c): a later mandatory-mode request carries the groups the targeted user is interested in at
    // its client: those notified "deaffiliating" (clause 9.3.1.2) are not.
    std::vector<std::string> groups;
    for (std::size_t index = 0; index < affiliation.groups.size(); ++index)
      if (client_rules::counts_as_affiliated(index < affiliation.statuses.size() ? affiliation.statuses[index] : ""))
        groups.push_back(affiliation.groups[index]);
    affiliation.groups = groups;
    affiliation.statuses.clear();
    state.target_affiliations[subject] = affiliation;
    event(service_key(service, "target_affiliation"), {{"target", subject}, {"groups", affiliation.groups}});
  } catch (const std::exception &error) {
    event(service_key(service, "target_affiliation_unread"), {{"target", subject}, {"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.5 (TS 24.281 clause 8.2.1.5): a MESSAGE with the service's affiliation command (the ICSI in
// P-Asserted-Service is checked by the caller): 1) 200 (OK) at once, then 2) the user's confirmation of the list of
// groups for affiliation and of the list for de-affiliation: the request is kept (status affiliation_command) until
// affiliation-accept or affiliation-decline.
SipResponse Client::affiliation_command_message(const ServiceProfile &service, const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  auto &state = procedures(service);
  const auto body = part_of(message, affiliation_command_mime(service));
  state.affiliation_command = {{"affiliate", command_groups(body, "affiliate")},
                               {"de-affiliate", command_groups(body, "de-affiliate")},
                               {"from", message.from_uri}};
  status_[service_key(service, "affiliation_command")] = state.affiliation_command;
  event(service_key(service, "affiliation_command_received"), state.affiliation_command);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.5 steps 2) and 3) (TS 24.281 clause 8.2.1.5): the user's confirmation: affiliation-accept
// [<group>...] accepts the request (only the groups named, when some are: the accepted lists), affiliation-decline
// declines it. Accepted: a) affiliation to each group of the accepted affiliation list not affiliated and b)
// de-affiliation from each group of the accepted de-affiliation list affiliated, as in clause 9.2.1.2; no other group
// changes.
void Client::affiliation_command_answer(const ServiceProfile &service, bool accept, const std::set<std::string> &only) {
  auto &state = procedures(service);
  if (!state.affiliation_command.is_object())
    throw std::runtime_error("no " + std::string(service.label) + " affiliation request");
  const auto command = std::exchange(state.affiliation_command, Json());
  status_[service_key(service, "affiliation_command")] = nullptr;
  event(service_key(service, accept ? "affiliation_command_accepted" : "affiliation_command_declined"));
  if (!accept) return;
  const auto accepted = [&only](std::vector<std::string> groups) {
    if (!only.empty()) std::erase_if(groups, [&only](const std::string &group) { return !only.contains(group); });
    return groups;
  };
  change_affiliation(service, accepted(command.value("affiliate", std::vector<std::string>{})),
                     accepted(command.value("de-affiliate", std::vector<std::string>{})));
}

//------------------------------------------------------------------------------
// The commands of these procedures, by verb without the service's prefix; false for another verb.
bool Client::procedures_command(const ServiceProfile &service, const std::string &verb, std::istringstream &words) {
  if (verb.starts_with("settings-")) {
    settings_command(service, verb, words);
    return true;
  }
  if (verb == "gdd-subscribe" || verb == "gdd-resubscribe" || verb == "gdd-unsubscribe") {
    gdd_command(service, verb, words);
    return true;
  }
  if (verb == "affiliation-subscribe" || verb == "affiliation-unsubscribe" || verb == "affiliate-remote" ||
      verb == "affiliation-accept" || verb == "affiliation-decline") {
    target_affiliation_command(service, verb, words);
    return true;
  }
  return false;
}

//------------------------------------------------------------------------------
// Helper: the status of these procedures (keys prefixed by service_key):
//   service_settings      {<client ID>: {answer_mode, profile_index, multiplex_support, own}}   (clause 7.2.4)
//   settings_publication  {action: "publish"|"remove", status, warning} or null                (clause 7.2.3)
//   affiliation_command   {affiliate: [...], de-affiliate: [...], from} or null                 (clause 9.2.1.5)
//   group_dynamic_data    {<group>: {subscription_status, group_status, modified_by, broadcast_alias, regroup_alias,
//                          call_ongoing, members: [{id, clients, functional_aliases}], notifications}} (clause 9.2.1.6)
//   target_affiliations   {<ID>: {client_id, groups}}                                           (clause 9.2.1.3)
void Client::procedures_status(const ServiceProfile &service) {
  const auto &state = procedures(service);
  status_[service_key(service, "service_settings")] = state.service_settings;
  status_[service_key(service, "settings_publication")] = state.settings_result;
  status_[service_key(service, "affiliation_command")] = state.affiliation_command;
  status_[service_key(service, "group_dynamic_data")] = state.gdd;
  Json targets = Json::object();
  for (const auto &[target, affiliation] : state.target_affiliations)
    targets[target] = {{"client_id", affiliation.client_id}, {"groups", affiliation.groups}};
  status_[service_key(service, "target_affiliations")] = targets;
}

} // namespace mcx
