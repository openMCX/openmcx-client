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
// TS 24.484 clause 7.2.2.7 item 7: the group management server XCAP Root URI (<GMS-XCAP-root-URI>, endpoints.gms),
// used as given; the documents are below it (RFC 4825 section 6).
std::string Client::gms_xcap_root() const { return cfg_.at("endpoints").at("gms").get<std::string>(); }

//------------------------------------------------------------------------------
// TS 24.484 clause 7.2.2.7 item 8: the configuration management server XCAP Root URI (<CMS-XCAP-root-URI>,
// endpoints.cms), used as given.
std::string Client::cms_xcap_root() const { return cfg_.at("endpoints").at("cms").get<std::string>(); }

//------------------------------------------------------------------------------
// Helper: the group documents on the group management server (AUID org.openmobilealliance.groups).
std::string Client::groups_xcap() const { return gms_xcap_root() + "/org.openmobilealliance.groups"; }

//------------------------------------------------------------------------------
// Helper: one XCAP request to the group management server; the answer is kept in status_["xcap_last"].
void Client::xcap_request(const std::string &method, const std::string &url, const std::string &content_type,
                          const std::string &body) {
  std::map<std::string, std::string> headers{{"Authorization", bearer()}};
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
  const auto url = cms_xcap_root() + "/org.3gpp.mcptt.user-profile/users/" +
                   url_encode(id) + "/mcptt-user-profile-" + std::to_string(index) + ".xml";
  const auto current = http_.request("GET", url, {}, {{"Authorization", bearer()}});
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
  std::map<std::string, std::string> headers{{"Authorization", bearer()},
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
// TS 24.481 clause 6.3.13.2.1: subscribe to the group documents of the groups (xcap-diff, RFC 5875); the dialog.
std::string Client::group_documents_subscribe(const std::vector<std::string> &args) {
  McpttInfo info;
  info.access_token = token_;
  info.client_id = client_id();
  auto parts = info_parts(info);
  std::vector<std::string> entries;
  for (const auto &group_id : args)
    entries.push_back("org.openmobilealliance.groups/global/byGroupID/" + url_encode(group_id));
  parts.push_back({resource_lists_mime, xcap_resource_list(entries)});
  const auto boundary = "mcx-" + random_id();
  // A refused SUBSCRIBE (403 when no group of the list may be followed, clause 6.3.13.3.2.2) creates no subscription
  // (RFC 6665 section 4.1.2.1), so it is not kept for a later re-SUBSCRIBE.
  auto created = std::make_shared<std::string>();
  const auto dialog = sip_.subscribe(
      gms_subscription_psi(), "xcap-diff", group_documents_expires_s(), {{"P-Preferred-Service", mcptt_icsi}},
      [this, args, created](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["group_subscription_result"] = {{"groups", args}, {"status", response.status_code}};
        if (response.status_code >= 300) schedule([this, created] { group_doc_subscriptions_.erase(*created); });
      },
      "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
  *created = dialog;
  group_doc_subscriptions_[dialog] = args;
  return dialog;
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1 with TS 24.484 clause 8.3.2: at service authorisation the GMC subscribes to the group
// documents of the user's groups, the selected group and the groups monitored besides it, in a SUBSCRIBE of its own
// next to the GKTP subscription (TS 37.579-1 table 5.3.2.7.1-1 steps 1c1 to 1c4); the NOTIFY is followed by the GET
// of each document (group_document_notify, steps 3 to 6). Once made, the subscription stays through a new
// authorisation; a refused one (no group may be followed) is not made again.
void Client::subscribe_own_group_documents() {
  if (!cfg_.at("mcptt").value("subscribe_group_documents", true) || !startup_group_subscription_.empty()) return;
  std::vector<std::string> groups;
  if (!group().empty()) groups.push_back(group());
  for (const auto &monitored : monitored_groups_)
    if (!monitored.empty() && std::find(groups.begin(), groups.end(), monitored) == groups.end())
      groups.push_back(monitored);
  if (groups.empty()) return;
  startup_group_subscription_ = group_documents_subscribe(groups);
  event("group_documents_subscribed", {{"groups", groups}});
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: re-subscribe to a modified list: a re-SUBSCRIBE in each group document subscription with
// one <entry> for each document to be subscribed to (the whole list, RFC 5875), the groups followed once answered 200.
void Client::group_documents_resubscribe(const std::vector<std::string> &args) {
  std::vector<std::string> entries;
  for (const auto &group_id : args)
    entries.push_back("org.openmobilealliance.groups/global/byGroupID/" + url_encode(group_id));
  // A subscription whose dialog is gone is dropped; the others are still re-subscribed.
  std::vector<std::string> failed;
  for (const auto &entry : group_doc_subscriptions_) {
    const auto dialog = entry.first;
    try {
      sip_.request_in_dialog(dialog, "SUBSCRIBE", resource_lists_mime, xcap_resource_list(entries),
                             {{"Event", "xcap-diff"}, {"Expires", std::to_string(group_documents_expires_s())}},
                             [this, dialog, args](const SipResponse &response) {
                               if (response.status_code < 200) return;
                               status_["group_subscription_result"] = {{"groups", args},
                                                                       {"status", response.status_code}};
                               if (response.status_code == 200)
                                 schedule([this, dialog, args] { group_doc_subscriptions_[dialog] = args; });
                             });
    } catch (const std::exception &error) {
      event("group_resubscribe_failed", {{"reason", error.what()}});
      failed.push_back(dialog);
    }
  }
  for (const auto &dialog : failed) group_doc_subscriptions_.erase(dialog);
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
// group-resubscribe <group ID>...
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
  } else if (verb == "group-resubscribe") { // TS 24.481 clause 6.3.13.2.1, re-SUBSCRIBE with a modified list
    if (args.empty()) throw std::runtime_error("group-resubscribe <group ID>...");
    group_documents_resubscribe(args);
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
// TS 24.484 clause 6.3.13.2.2 item b: the configuration management client subscribes to several documents at once
// through the subscription proxy function of the CMS (SIP SUBSCRIBE, event package xcap-diff of RFC 5875), one
// <entry> for each, relative to the CMS XCAP root (test view TS 37.579-1 table 5.3.2.6.1-1 step 1 and table
// 5.5.3.3.1A-1): the MCPTT UE configuration document (clause 8.2.1A), the MCPTT user profile document (clause
// 8.3.1A) and the MCPTT service configuration document (clause 8.4.1); item c, the access token in the mcptt-info.
void Client::subscribe_configuration() {
  McpttInfo info;
  info.access_token = token_;
  info.client_id = client_id();
  auto parts = info_parts(info);
  const std::vector<std::string> documents{ue_configuration_document_path(), profile_document_path(),
                                           service_configuration_document_path};
  parts.push_back({resource_lists_mime, xcap_resource_list(documents)});
  const auto boundary = "mcx-" + random_id();
  config_subscription_ = sip_.subscribe(
      service(), "xcap-diff", configuration_documents_expires_s(), {{"P-Preferred-Service", mcptt_icsi}},
      [this](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["configuration"]["subscription_status"] = response.status_code;
        if (response.status_code < 300) return configuration_subscription_granted(response);
        schedule([this, code = response.status_code] {
          config_subscription_.clear();
          config_expires_at_ = 0;
          // A sign-in waiting for the MCPTT service configuration document tries again later.
          if (awaiting_mcptt_configuration_) sign_in_failed("configuration subscription: " + std::to_string(code));
        });
      },
      "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
  status_["configuration"]["subscribed_documents"] = documents;
  event("configuration_subscribed", {{"documents", documents}});
}

//------------------------------------------------------------------------------
// RFC 6665 section 4.1.2.2 (TS 24.484 clause 6.3.13.2.2): the configuration subscription refreshed by a SUBSCRIBE in
// its dialog with the same Expires as the initial one, on a later sign-in attempt (start_authorization). The CMS
// answers with a NOTIFY (RFC 6665 section 4.2.2), which configuration_notified() handles. On a 404, 405, 410, 416,
// 480-485, 489, 501 or 604 the subscriber "MUST consider the subscription terminated", as when the refresh cannot be
// sent; the next sign-in attempt subscribes anew. On any other error "the original subscription is still considered
// valid for the duration of the most recently known "Expires" value" (configuration_subscription_tick).
void Client::refresh_configuration_subscription() {
  if (config_subscription_.empty()) return;
  const auto id = config_subscription_;
  try {
    sip_.request_in_dialog(id, "SUBSCRIBE", {}, {},
                           {{"Event", "xcap-diff"},
                            {"Expires", std::to_string(configuration_documents_expires_s())},
                            {"P-Preferred-Service", mcptt_icsi}},
                           [this, id](const SipResponse &response) {
                             if (response.status_code < 200 || config_subscription_ != id) return;
                             status_["configuration"]["subscription_status"] = response.status_code;
                             const auto code = response.status_code;
                             if (code < 300) {
                               configuration_subscription_granted(response);
                               return event("configuration_subscription_refreshed");
                             }
                             const auto reason = std::to_string(code);
                             if (code == 404 || code == 405 || code == 410 || code == 416 ||
                                 (code >= 480 && code <= 485) || code == 489 || code == 501 || code == 604)
                               return schedule([this, reason] { configuration_subscription_ended(reason); });
                             event("configuration_subscription_refresh_failed",
                                   {{"reason", reason}, {"expires_at", config_expires_at_}});
                           });
  } catch (const std::exception &error) {
    configuration_subscription_ended(error.what());
  }
}

//------------------------------------------------------------------------------
// RFC 6665 section 4.1.2.1: the Expires of the 2xx "indicates the actual duration for which the subscription will
// remain active" (without one, the duration asked for).
void Client::configuration_subscription_granted(const SipResponse &response) {
  std::int64_t granted = configuration_documents_expires_s();
  try {
    if (const auto value = get_header(response.headers, "Expires"); !value.empty())
      granted = std::stoll(std::string(value));
  } catch (const std::exception &) {}
  config_expires_at_ = granted > 0 ? std::time(nullptr) + granted : 0;
  status_["configuration"]["subscription_expires_at"] = config_expires_at_;
}

//------------------------------------------------------------------------------
// The configuration subscription is over: a refresh answered with one of the codes of RFC 6665 section 4.1.2.2, its
// expiry (section 4.1.2.1), or a NOTIFY with Subscription-State "terminated" (section 4.1.3: the subscriber "MUST
// consider the subscription terminated"). Nothing is sent; a later sign-in attempt subscribes anew
// (start_authorization).
void Client::configuration_subscription_ended(const std::string &reason) {
  config_subscription_.clear();
  config_expires_at_ = 0;
  event("configuration_subscription_ended", {{"reason", reason}});
}

//------------------------------------------------------------------------------
// From tick(): the end of the duration the last 2xx granted (RFC 6665 section 4.1.2.1).
void Client::configuration_subscription_tick() {
  if (!config_subscription_.empty() && config_expires_at_ && std::time(nullptr) >= config_expires_at_)
    configuration_subscription_ended("expired");
}

//------------------------------------------------------------------------------
// TS 24.484 clause 8.2.1A: the MCPTT UE configuration document of this UE, users/sip:<MCPTT ID>/<MCSUEID> (the UE
// instance ID of mcptt.ue_instance_id, else the MCPTT client ID).
std::string Client::ue_configuration_document_path() const {
  const auto &mcptt = cfg_.at("mcptt");
  const auto ue = mcptt.contains("ue_instance_id") ? mcptt.at("ue_instance_id").get<std::string>() : client_id();
  return "org.3gpp.mcptt.ue-config/users/" + url_encode(user()) + "/" + url_encode(ue);
}

//------------------------------------------------------------------------------
// TS 24.484 clauses 6.3.3.2.2 and 8.2.1A: retrieve the MCPTT UE configuration document (test view TS 37.579-1 table
// 5.3.2.6.1-1 step 5). A CMS that has none for this UE (404) or that cannot be reached leaves the client as it is:
// the preconfigured values stay in use.
void Client::read_ue_configuration() {
  const auto path = "/" + ue_configuration_document_path();
  Json state{{"document", ue_configuration_document_path()}};
  try {
    const auto response = http_.request("GET", cms_xcap_root() + path, {},
                                        {{"Authorization", bearer()}});
    state["status"] = response.status;
    if (response.status == 200) {
      state["etag"] = response.headers.contains("etag") ? response.headers.at("etag") : std::string{};
      state["bytes"] = response.body.size();
      // TS 24.484 clause 8.2.2.1: the document's root element is <mcptt-UE-configuration>.
      state["valid"] = response.body.find("mcptt-UE-configuration") != std::string::npos;
      event("document_retrieved", {{"resource", path}, {"bytes", response.body.size()}});
    } else {
      event("ue_configuration_unavailable", {{"status", response.status}});
    }
  } catch (const std::exception &error) {
    state["status"] = 0;
    event("ue_configuration_unavailable", {{"reason", error.what()}});
  }
  status_["ue_configuration"] = state;
}

//------------------------------------------------------------------------------
// TS 24.484 clause 6.3.13.2.2 (RFC 5875): on a NOTIFY of the configuration subscription, retrieve the subscribed
// documents again (TS 24.484 clause 6.3.3.2.2; test view TS 37.579-1 table 5.3.2.6.1-1 steps 5, 7 and 9): the UE
// configuration, the user profile, whose actions are taken when its entity tag changed, and the service
// configuration (its Resource-Priority values).
void Client::configuration_notified() {
  ++config_notifies_;
  read_ue_configuration();
  const auto response =
      http_.request("GET", cms_xcap_root() + "/" + profile_document_path(),
                    {}, {{"Authorization", bearer()}});
  if (response.status == 200) {
    event("document_retrieved",
          {{"resource", "/" + profile_document_path()}, {"bytes", response.body.size()}});
    const auto etag = response.headers.contains("etag") ? response.headers.at("etag") : std::string{};
    if (etag != status_["configuration"].value("etag", std::string{})) {
      if (status_["configuration"].contains("etag")) ++config_refreshes_;
      profile_actions_ = parse_profile_actions(response.body);
      take_profile_groups(response.body);
      profile_loaded_ = true;
      status_["configuration"]["etag"] = etag;
      event("configuration_refreshed", {{"etag", etag}, {"actions", profile_actions_.size()}});
    }
  }
  read_service_configurations({"mcptt"});
  status_["configuration"]["notifies"] = config_notifies_;
  status_["configuration"]["refreshes"] = config_refreshes_;
  // The sign-in waiting for this document (start_authorization): its keys, then the service authorisation; a document
  // that could not be read fails this attempt (sign_in_failed: another one later).
  if (awaiting_mcptt_configuration_ && !protection_of("mcptt"))
    return sign_in_failed("the mcptt service configuration document could not be read");
  if (awaiting_mcptt_configuration_) {
    awaiting_mcptt_configuration_ = false;
    try {
      provision_for_protection();
    } catch (const std::exception &error) {
      return sign_in_failed(error.what());
    }
    if (registered_) authorize();
  }
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: the GMC handles the NOTIFY of a group document subscription by RFC 5875: the changed
// documents are read again.
std::optional<SipResponse> Client::group_document_notify(const SipMessage &message) {
  const auto found = group_doc_subscriptions_.find(message.dialog_id);
  if (found == group_doc_subscriptions_.end()) return std::nullopt;
  SipResponse response;
  response.status_code = 200;
  if (!from_gms_proxy(message, false)) {
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
                                            {{"Authorization", bearer()}});
        auto &document = group_documents_[group];
        document["fetch_status"] = response.status;
        if (response.status == 200)
          event("document_retrieved",
                {{"resource", "/org.openmobilealliance.groups/global/byGroupID/" + url_encode(group)},
                 {"bytes", response.body.size()}});
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
