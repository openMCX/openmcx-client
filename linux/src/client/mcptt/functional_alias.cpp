// Functional aliases: the MCPTT client's functional alias status change (activation, takeover, deactivation).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.2: the functional alias status change procedure: send a SIP PUBLISH with the
// functional alias request to activate, take over or deactivate aliases; record the result and signal the location
// trigger. Command:
//   fa activate|takeover|deactivate <alias>...   fa use <alias>|none
void Client::functional_alias_command(const std::string &arguments) {
  std::istringstream words(arguments);
  std::string action, alias;
  words >> action;
  std::vector<std::string> aliases;
  while (words >> alias) aliases.push_back(alias);
  if (action == "use") {
    if (aliases.size() != 1) throw std::runtime_error("usage: fa use <alias>|none");
    functional_alias_ = aliases.front() == "none" ? std::string{} : aliases.front();
    return;
  }
  if ((action != "activate" && action != "takeover" && action != "deactivate") || aliases.empty() || !authorized_)
    throw std::runtime_error("usage: fa activate|takeover|deactivate <alias>...");
  if (action == "takeover") require_profile({"allow-takeover-functional-alias-other-user"});
  FunctionalAliasRequest request{user(), client_id(), random_id(), aliases, action == "takeover"};
  McpttInfo info;
  info.request_uri = user();
  info.client_id = client_id();
  const auto boundary = "mcx-" + random_id();
  const auto body =
      multipart_body({info_parts(info).front(), {"application/pidf+xml", functional_alias_xml(request)}}, boundary);
  sip_.publish(service(), "multipart/mixed; boundary=" + boundary, body,
               {{"Event", "presence"},
                {"Expires", action == "deactivate" ? "0" : "4294967295"},
                {"P-Preferred-Service", mcptt_icsi}},
               [this, action, aliases](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 status_["last_fa_result"] = {
                     {"action", action}, {"aliases", aliases}, {"status", response.status_code}};
                 if (response.status_code == 200) {
                   for (const auto &requested : aliases) {
                     std::erase(requested_aliases_, requested);
                     if (action != "deactivate") requested_aliases_.push_back(requested);
                   }
                   if (!aliases.empty()) location_alias_ = aliases.front();
                   location_signal(action == "deactivate" ? location::Event::FunctionalAliasDeactivation
                                                          : location::Event::FunctionalAliasActivation);
                 } else
                   failure("functional alias " + action + ": " + std::to_string(response.status_code));
               });
  event("functional_alias_requested", {{"action", action}, {"aliases", aliases}});
}

} // namespace mcx
