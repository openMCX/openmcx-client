//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Group management client (TS 24.481 clauses 6.3.2 to 6.3.15) and configuration management client
// (TS 24.484 clauses 6.3.3, 6.3.4, 6.3.13.2.2). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.481 V19.3.0 (Rel-19), TS 24.484 V19.6.0 (Rel-19), RFC 5875.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: the XCAP root of the group documents on the group management server (AUID org.openmobilealliance.groups).
std::string Client::groups_xcap() const {
  return cfg_.at("endpoints").at("gms").get<std::string>() + "/xcap-root/org.openmobilealliance.groups";
}

//------------------------------------------------------------------------------
// Helper: one XCAP request to the group management server; the answer is kept in status_["xcap_last"].
void Client::xcap_request(const std::string &method, const std::string &url, const std::string &content_type,
                          const std::string &body) {
  std::map<std::string, std::string> headers{{"Authorization", "Bearer " + token_}};
  if (!content_type.empty()) headers["Content-Type"] = content_type;
  const auto response = http_.request(method, url, body, headers);
  // "seq" tells one answer from the next, for a caller reading the status.
  status_["xcap_last"] = {{"method", method},
                          {"url", url},
                          {"status", response.status},
                          {"body", response.body.substr(0, 1000)},
                          {"etag", response.headers.contains("etag") ? response.headers.at("etag") : ""},
                          {"seq", ++xcap_seq_}};
  event("xcap_request", {{"method", method}, {"status", response.status}});
}

//------------------------------------------------------------------------------
// Helper: a group document (TS 24.481 clause 7.2) of the group with its members.
std::string Client::group_document_xml(const std::string &group, const std::vector<std::string> &members) {
  std::string out =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?><group xmlns=\"urn:oma:xml:poc:list-service\"><list-service uri=\"" +
      xml_escape(group) + "\"><list>";
  for (const auto &member : members) out += "<entry uri=\"" + xml_escape(member) + "\"/>";
  // TS 24.481 clause 7.2.12.1: the members may read the member list.
  return out +
         "</list><cp:ruleset xmlns:cp=\"urn:ietf:params:xml:ns:common-policy\"><cp:rule "
         "id=\"members\"><cp:conditions>"
         "<is-list-member/></cp:conditions><cp:actions><on-network-allow-getting-member-list "
         "xmlns=\"urn:3gpp:ns:mcpttGroupInfo:1.0\">true</on-network-allow-getting-member-list></cp:actions></cp:rule>"
         "</cp:ruleset></list-service></group>";
}

//------------------------------------------------------------------------------
// TS 24.484 clauses 6.3.3.2.2 and 6.3.4.2: the configuration management client retrieves a user's profile document
// and updates it. "profile-revoke <MCPTT ID> <action> [<profile index>]": an administrator removes an action from a
// user's profile document, mcptt-user-profile-<index>.xml (clause 8.3.1A; index 1 by default), and replaces it.
void Client::profile_command(const std::string &cmd) {
  std::istringstream words(cmd);
  std::string verb, id, action;
  unsigned index = 1;
  words >> verb >> id >> action;
  if (!(words >> index)) index = 1;
  if (verb != "profile-revoke" || id.empty() || action.empty() || index < 1 || index > 255)
    throw std::runtime_error("profile-revoke <MCPTT ID> <action> [<profile index>]");
  const auto url = cfg_.at("endpoints").at("cms").get<std::string>() + "/xcap-root/org.3gpp.mcptt.user-profile/users/" +
                   url_encode(id) + "/mcptt-user-profile-" + std::to_string(index) + ".xml";
  const auto current = http_.request("GET", url, {}, {{"Authorization", "Bearer " + token_}});
  if (current.status != 200) {
    status_["xcap_last"] = {{"method", "GET"},
                            {"url", url},
                            {"status", current.status},
                            {"body", current.body.substr(0, 1000)},
                            {"etag", ""}};
    return;
  }
  // TS 24.484 clause 8.3.2.1 item 11: a right is a boolean element of the ruleset
  // actions; it is revoked by setting it to false.
  auto body = current.body;
  bool revoked = false;
  for (std::size_t at = 0; !revoked && (at = body.find("<" + action, at)) != std::string::npos; ++at) {
    const auto after = at + 1 + action.size();
    if (after >= body.size() || (body[after] != '>' && body[after] != ' ')) continue;
    const auto open_end = body.find('>', after);
    if (open_end == std::string::npos || body[open_end - 1] == '/') continue;
    const auto close = body.find("</" + action + ">", open_end);
    if (close == std::string::npos) continue;
    auto value = body.substr(open_end + 1, close - open_end - 1);
    value.erase(0, value.find_first_not_of(" \t\r\n"));
    value.erase(value.find_last_not_of(" \t\r\n") + 1);
    if (value != "true" && value != "1") continue;
    body.replace(open_end + 1, close - open_end - 1, "false");
    revoked = true;
  }
  if (!revoked) throw std::runtime_error("the profile does not grant " + action);

  // The MIME type of the user profile document (TS 24.484 clause 8.3.2.5).
  std::map<std::string, std::string> headers{{"Authorization", "Bearer " + token_},
                                             {"Content-Type", "application/vnd.3gpp.mcptt-user-profile+xml"}};
  if (current.headers.contains("etag")) headers["If-Match"] = current.headers.at("etag");
  const auto response = http_.request("PUT", url, body, headers);
  status_["xcap_last"] = {{"method", "PUT"},
                          {"url", url},
                          {"status", response.status},
                          {"body", response.body.substr(0, 1000)},
                          {"etag", response.headers.contains("etag") ? response.headers.at("etag") : ""}};
  event("xcap_request", {{"method", "PUT"}, {"status", response.status}});
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.14.2 with clause 7.3.4.3: the GMOP group regroup creation request of a temporary group: its
// group document lists the constituent groups (comma-separated in groups) in <on-network-temporary>.
std::string Client::temporary_group_request_xml(const std::string &group_id, const std::string &groups, bool chat,
                                                bool protect) {
  std::string ids;
  for (std::size_t start = 0; start <= groups.size();) {
    const auto end = std::min(groups.find(',', start), groups.size());
    if (end > start)
      ids += "<mcpttgi:constituent-MCPTT-group-ID>" + xml_escape(groups.substr(start, end - start)) +
             "</mcpttgi:constituent-MCPTT-group-ID>";
    start = end + 1;
  }
  const std::string flag = protect ? "true" : "false";

  // The group document of the temporary group, in a GMOP group regroup creation
  // request (TS 24.481 clause 7.3.4.3): its <on-network-temporary> lists the constituent groups.
  std::string group =
      "<group xmlns=\"urn:oma:xml:poc:list-service\" xmlns:mcpttgi=\"urn:3gpp:ns:mcpttGroupInfo:1.0\">"
      "<list-service uri=\"" +
      xml_escape(group_id) +
      "\">"
      "<mcpttgi:protect-media>" +
      flag +
      "</mcpttgi:protect-media>"
      "<mcpttgi:protect-floor-control-signalling>" +
      flag +
      "</mcpttgi:protect-floor-control-signalling>"
      "<mcpttgi:on-network-temporary><mcpttgi:constituent-MCPTT-group-IDs>" +
      ids + "</mcpttgi:constituent-MCPTT-group-IDs></mcpttgi:on-network-temporary>";
  // The group's type, in the project's configuration extension of the document.
  if (chat)
    group += "<configuration xmlns=\"urn:openmcx:ns:groupConfiguration:1.0\">{\"type\":\"chat\"}</configuration>";
  group += "</list-service></group>";
  return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><document xmlns=\"urn:3gpp:ns:mcpttGMOP:1.0\"><request>"
         "<group-regroup-creation>" +
         group + "</group-regroup-creation></request></document>";
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: subscribe to the group documents of the groups (xcap-diff, RFC 5875).
void Client::group_documents_subscribe(const std::vector<std::string> &args) {
  McpttInfo info;
  info.access_token = token_;
  info.client_id = client_id();
  auto parts = info_parts(info);
  std::vector<std::string> entries;
  for (const auto &group_id : args)
    entries.push_back("org.openmobilealliance.groups/global/byGroupID/" + url_encode(group_id));
  parts.push_back({resource_lists_mime, xcap_resource_list(entries)});
  const auto boundary = "mcx-" + random_id();
  const auto dialog = sip_.subscribe(
      service(), "xcap-diff", 600, {{"P-Preferred-Service", mcptt_icsi}},
      [this, args](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["group_subscription_result"] = {{"groups", args}, {"status", response.status_code}};
      },
      "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
  group_doc_subscriptions_[dialog] = args;
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: end every group document subscription with a re-SUBSCRIBE with Expires 0.
void Client::group_documents_unsubscribe() {
  // The final NOTIFY (Subscription-State terminated) removes a subscription (group_document_notify); one whose
  // re-SUBSCRIBE fails or is refused is removed at once.
  std::vector<std::string> failed;
  for (const auto &entry : group_doc_subscriptions_) {
    const auto dialog = entry.first;
    try {
      sip_.request_in_dialog(dialog, "SUBSCRIBE", {}, {}, {{"Event", "xcap-diff"}, {"Expires", "0"}},
                             [this, dialog](const SipResponse &response) {
                               if (response.status_code >= 300)
                                 schedule([this, dialog] { group_doc_subscriptions_.erase(dialog); });
                             });
    } catch (const std::exception &error) {
      event("group_unsubscribe_failed", {{"reason", error.what()}});
      failed.push_back(dialog);
    }
  }
  for (const auto &dialog : failed) group_doc_subscriptions_.erase(dialog);
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3: the group management client procedures, as test commands:
// group-create <document> <group ID> <member>...   group-delete <document>
// group-member add|remove <group ID> <member>
// group-subscribe <group ID>...                    group-unsubscribe
// temporary-create ...                             temporary-delete <document>
void Client::group_management_command(const std::string &cmd) {
  std::istringstream in(cmd);
  std::string verb;
  in >> verb;
  std::vector<std::string> args;
  for (std::string word; in >> word;) args.push_back(word);
  const auto own_tree = groups_xcap() + "/users/" + url_encode(user()) + "/";
  if (verb == "group-create") { // TS 24.481 clause 6.3.2.2.2
    if (args.size() < 2) throw std::runtime_error("group-create <document> <group ID> <member>...");
    xcap_request("PUT", own_tree + url_encode(args[0]), "application/vnd.oma.poc.groups+xml",
                 group_document_xml(args[1], {args.begin() + 2, args.end()}));
  } else if (verb == "temporary-create") { // TS 24.481 clause 6.3.14.2
    // temporary-create <document> <temporary group ID> <group>,<group>... [chat] [protected]
    if (args.size() < 3)
      throw std::runtime_error(
          "temporary-create <document> <temporary group ID> <group>,<group>... [chat] [protected]");
    const bool chat = std::find(args.begin() + 3, args.end(), "chat") != args.end();
    const bool protect = std::find(args.begin() + 3, args.end(), "protected") != args.end();
    xcap_request("POST", own_tree + url_encode(args[0]), "application/vnd.3gpp.GMOP+xml",
                 temporary_group_request_xml(args[1], args[2], chat, protect));
  } else if (verb == "temporary-delete") { // TS 24.481 clause 6.3.15.2
    if (args.size() != 1) throw std::runtime_error("temporary-delete <document>");
    xcap_request("DELETE", own_tree + url_encode(args[0]));
  } else if (verb == "group-delete") { // TS 24.481 clause 6.3.5.2.2
    if (args.size() != 1) throw std::runtime_error("group-delete <document>");
    xcap_request("DELETE", own_tree + url_encode(args[0]));
  } else if (verb == "group-member") { // TS 24.481 clauses 6.3.6.2.2 and 6.3.7.2.2
    if (args.size() != 3 || (args[0] != "add" && args[0] != "remove"))
      throw std::runtime_error("group-member add|remove <group ID> <member>");
    const auto url = groups_xcap() + "/global/byGroupID/" + url_encode(args[1]) + "/~~/" +
                     url_encode("group/list-service/list/entry[@uri=\"" + args[2] + "\"]");
    if (args[0] == "add")
      xcap_request("PUT", url, "application/xcap-el+xml",
                   "<entry xmlns=\"urn:oma:xml:poc:list-service\" uri=\"" + xml_escape(args[2]) + "\"/>");
    else
      xcap_request("DELETE", url);
  } else if (verb == "group-subscribe") { // TS 24.481 clause 6.3.13.2.1
    if (args.empty()) throw std::runtime_error("group-subscribe <group ID>...");
    group_documents_subscribe(args);
  } else if (verb == "group-unsubscribe") { // TS 24.481 clause 6.3.13.2.1, re-SUBSCRIBE with Expires 0
    group_documents_unsubscribe();
  } else
    throw std::runtime_error("unknown group management command");
}

//------------------------------------------------------------------------------
// Helper: keep the groups of the MCPTT user profile (TS 24.484 clause 8.3.2), and show them in the status file.
void Client::take_profile_groups(const std::string &profile) {
  profile_groups_ = client_rules::parse_profile_groups(profile);
  status_["profile_groups"] = profile_groups_;
}

//------------------------------------------------------------------------------
// TS 24.484 clause 6.3.13.2.2: the configuration management client subscribes to changes of the user profile
// document (SIP SUBSCRIBE, event package xcap-diff of RFC 5875).
void Client::subscribe_configuration() {
  McpttInfo info;
  info.access_token = token_;
  info.client_id = client_id();
  auto parts = info_parts(info);
  parts.push_back({resource_lists_mime, xcap_resource_list({profile_document_path()})});
  const auto boundary = "mcx-" + random_id();
  config_subscription_ = sip_.subscribe(
      service(), "xcap-diff", 3600, {{"P-Preferred-Service", mcptt_icsi}},
      [this](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["configuration"]["subscription_status"] = response.status_code;
        if (response.status_code >= 300) schedule([this] { config_subscription_.clear(); });
      },
      "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
  event("configuration_subscribed", {{"document", profile_document_path()}});
}

//------------------------------------------------------------------------------
// TS 24.484 clause 6.3.13.2.2: on a NOTIFY of the configuration subscription, retrieve the user profile document again
// (TS 24.484 clause 6.3.3.2.2) and take its actions when its entity tag changed.
void Client::configuration_notified() {
  ++config_notifies_;
  const auto response =
      http_.request("GET", cfg_.at("endpoints").at("cms").get<std::string>() + "/xcap-root/" + profile_document_path(),
                    {}, {{"Authorization", "Bearer " + token_}});
  if (response.status == 200) {
    const auto etag = response.headers.contains("etag") ? response.headers.at("etag") : std::string{};
    if (etag != status_["configuration"].value("etag", std::string{})) {
      if (status_["configuration"].contains("etag")) ++config_refreshes_;
      profile_actions_ = parse_profile_actions(response.body);
      take_profile_groups(response.body);
      status_["configuration"]["etag"] = etag;
      event("configuration_refreshed", {{"etag", etag}, {"actions", profile_actions_.size()}});
    }
  }
  status_["configuration"]["notifies"] = config_notifies_;
  status_["configuration"]["refreshes"] = config_refreshes_;
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: the GMC handles the NOTIFY of a group document subscription by RFC 5875: the changed
// documents are read again.
std::optional<SipResponse> Client::group_document_notify(const SipMessage &message) {
  const auto found = group_doc_subscriptions_.find(message.dialog_id);
  if (found == group_doc_subscriptions_.end()) return std::nullopt;
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  const auto body = message.parts.empty() ? message.body : message.parts.front().body;
  const auto attribute = [&](const std::string &element, const char *name) {
    const auto at = element.find(std::string(name) + "=\"");
    if (at == std::string::npos) return std::string{};
    const auto start = at + std::strlen(name) + 2;
    return element.substr(start, element.find('"', start) - start);
  };
  for (std::size_t at = body.find("<document"); at != std::string::npos; at = body.find("<document", at + 1)) {
    const auto element = body.substr(at, body.find("/>", at) - at);
    auto sel = attribute(element, "sel");
    // The group ID, percent-encoded as the last step of the selector (a bad escape is kept as it is).
    const auto group = client_rules::percent_decode(std::string_view(sel).substr(sel.rfind('/') + 1));
    auto &entry = group_documents_[group];
    if (!entry.is_object()) entry = Json::object();
    entry["notifications"] = entry.value("notifications", 0) + 1;
    auto etag = attribute(element, "new-etag");
    for (std::size_t quote; (quote = etag.find("&quot;")) != std::string::npos;) etag.replace(quote, 6, "\"");
    entry["etag"] = etag;
    entry["removed"] = entry["etag"].get<std::string>().empty();
    if (!entry["removed"].get<bool>()) {
      schedule([this, group] {
        // TS 24.481 clause 6.3.3.2.2: retrieve the changed group document.
        const auto response = http_.request("GET", groups_xcap() + "/global/byGroupID/" + url_encode(group), {},
                                            {{"Authorization", "Bearer " + token_}});
        auto &document = group_documents_[group];
        document["fetch_status"] = response.status;
        Json members = Json::array();
        for (std::size_t at = response.body.find("<entry"); at != std::string::npos;
             at = response.body.find("<entry", at + 1)) {
          const auto uri = response.body.find("uri=\"", at);
          if (uri != std::string::npos)
            members.push_back(response.body.substr(uri + 5, response.body.find('"', uri + 5) - uri - 5));
        }
        document["members"] = members;
      });
    }
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated"))
    group_doc_subscriptions_.erase(found);
  event("group_document_notified", {{"documents", group_documents_.size()}});
  return response;
}

} // namespace mcx
