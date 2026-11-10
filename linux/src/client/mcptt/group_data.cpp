//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Subscription to group dynamic data (TS 24.379 clause 9.2.1.6): the MCPTT client subscribes to a group's per-group
// dynamic data (here the group call ongoing information, clause 9.3.2.2 item 7 B d) and reads the notifications
// (clause 9.3.1). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), IETF RFC 3856, RFC 4661, RFC 6665.

#include "client.hpp"

#include <regex>

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.6 items 2 and 7: the MCPTT-Info naming the group (<mcptt-request-uri>) and the SIMPLE-FILTER
// with the per-group restrictions of clause 9.3.2.2 (the "pidf" and "mcpttPI10" ns-bindings, a filter without "uri" or
// "domain", the group call ongoing information included).
std::pair<std::string, std::string> Client::group_data_body(const std::string &group) {
  McpttInfo info;
  info.request_uri = group;
  auto parts = info_parts(info);
  parts.push_back({"application/simple-filter+xml",
                   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                   "<filter-set xmlns=\"urn:ietf:params:xml:ns:simple-filter\"><ns-bindings>"
                   "<ns-binding prefix=\"pidf\" urn=\"urn:ietf:params:xml:ns:pidf\"/>"
                   "<ns-binding prefix=\"mcpttPI10\" urn=\"urn:3gpp:ns:mcpttPresInfo:1.0\"/>"
                   "</ns-bindings><filter id=\"" +
                       random_id().substr(0, 12) +
                       "\"><what><include>//pidf:presence/pidf:additionalData/@pidf:groupCallOngoing</include>"
                       "</what></filter></filter-set>"});
  const auto boundary = "mcx-" + random_id();
  return {"multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary)};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.6: "group-data-subscribe [<group>]" (the selected group by default): the initial SUBSCRIBE to
// the participating function (item 1) with Expires 4294967295 (item 4: the current status and later notifications),
// P-Preferred-Service (item 3), Accept application/pidf+xml (item 6); "group-data-unsubscribe": the in-dialog
// SUBSCRIBE with Expires 0 (de-subscription, item 2 of the re-subscription). The de-subscription repeats the
// MCPTT-Info and SIMPLE-FILTER bodies (TS 37.579-2 table 5.7.3.3-6; the clause asks only for the Accept).
void Client::group_data_command(const std::string &cmd) {
  if (!authorized_) throw std::runtime_error("authorization required");
  const std::vector<SipHeader> common{{"P-Preferred-Service", mcptt_icsi}, {"Accept", "application/pidf+xml"}};
  if (cmd == "group-data-unsubscribe") {
    if (group_data_dialog_.empty()) throw std::runtime_error("no group dynamic data subscription");
    auto [type, body] = group_data_body(group_data_group_);
    auto headers = common;
    headers.insert(headers.begin(), {{"Event", "presence"}, {"Expires", "0"}});
    sip_.request_in_dialog(group_data_dialog_, "SUBSCRIBE", type, body, headers, [this](const SipResponse &response) {
      if (response.status_code < 200) return;
      status_["group_data_result"] = {{"action", "unsubscribe"}, {"status", response.status_code}};
      if (response.status_code >= 300) group_data_dialog_.clear();
    });
    event("group_data_unsubscribe", {{"group", group_data_group_}});
    return;
  }
  if (!cmd.starts_with("group-data-subscribe")) throw std::runtime_error("group-data-subscribe [<group>] | group-data-unsubscribe");
  if (!group_data_dialog_.empty()) throw std::runtime_error("already subscribed to group dynamic data");
  const auto target = cmd.size() > 21 ? cmd.substr(21) : group();
  if (target.empty()) throw std::runtime_error("no group");
  group_data_group_ = target;
  auto [type, body] = group_data_body(target);
  group_data_dialog_ = sip_.subscribe(
      service(), "presence", 4294967295u, common,
      [this](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["group_data_result"] = {{"action", "subscribe"}, {"status", response.status_code}};
        if (response.status_code >= 300) {
          failure("group dynamic data subscription: " + std::to_string(response.status_code));
          group_data_dialog_.clear();
        }
      },
      type, body);
  event("group_data_subscribe", {{"group", target}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.6, last paragraph: the per-group dynamic data of a NOTIFY (clause 9.3.1): the group in
// "entity", the affiliated members (the <tuple> ids) and the group call ongoing information (<additionalData>). A
// terminated subscription is forgotten (RFC 6665 section 4.1.3).
SipResponse Client::group_data_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  std::string pidf;
  for (const auto &part : message.parts.empty() ? parse_body_parts(message.content_type, message.body) : message.parts)
    if (part.content_type == "application/pidf+xml") pidf = part.body;
  if (pidf.empty() && message.content_type.starts_with("application/pidf+xml")) pidf = message.body;
  Json entry = {{"subscribed", true}};
  static const std::regex ongoing(R"(groupCallOngoing\s*=\s*"([^"]*)\")"), tuple(R"(<(?:\w+:)?tuple\b[^>]*\sid="([^"]+)\")");
  std::smatch hit;
  if (std::regex_search(pidf, hit, ongoing)) entry["call_ongoing"] = hit[1] == "true" || hit[1] == "1";
  std::vector<std::string> members;
  for (auto it = std::sregex_iterator(pidf.begin(), pidf.end(), tuple); it != std::sregex_iterator(); ++it)
    members.push_back((*it)[1]);
  entry["affiliated_members"] = members;
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    entry["subscribed"] = false;
    group_data_dialog_.clear();
  }
  status_["group_dynamic_data"][group_data_group_] = entry;
  event("group_data_notify", {{"group", group_data_group_}, {"data", entry}});
  return response;
}

} // namespace mcx
