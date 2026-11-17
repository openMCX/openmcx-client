//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo service settings and affiliation: the poc-settings of this client (TS 24.281 clauses 7.2.2 6) and 7.2.3 4))
// and the affiliation change of clause 8.2.1.5 3) (as in clause 8.2.1.2). The settings-only PUBLISH (clause 7.2.3),
// the settings subscription (clause 7.2.4), the affiliation procedures towards other users (clauses 8.2.1.2 to
// 8.2.1.5) and the group dynamic data subscription (clause 8.2.1.6) are shared with MCPTT
// (core/settings_affiliation.cpp, mcvideo_procedures_).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), IETF RFC 4354, RFC 3903.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clauses 7.2.2 6) and 7.2.3 4): the application/poc-settings+xml of this client: a) the current answer mode
// (mcvideo.answer_mode, RFC 4354 "automatic" or "manual"), b) the "user-profile-index" of the selected MCVideo user
// profile (TS 24.484), c) <multiplex-support> (clause 7.4.1.2.2).
std::string Client::mcvideo_poc_settings() {
  unsigned index = profile_index_;
  if (const auto found = mcvideo_profile_.find("profile_index");
      found != mcvideo_profile_.end() && found->is_number_unsigned())
    index = found->get<unsigned>();
  const bool manual = cfg_.at("mcvideo").value("answer_mode", std::string("auto")) == "manual";
  return poc_settings_xml(client_id(), index, manual);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 8.3.2.2 (RFC 4661): the simple-filter restricting presence notifications to this MCVideo client.
std::string Client::mcvideo_client_filter() { return client_filter_xml(mcvideo_service); }

//------------------------------------------------------------------------------
// TS 24.281 clauses 8.2.1.2 and 8.2.1.5 3): one PUBLISH with the affiliation changed: a) the groups of `affiliate`
// the client is not affiliated to join it, b) the groups of `leave` it is affiliated to leave it; the other groups
// stay (step 5 c): the request names all the groups the user is interested in). Affiliated: in the client's own set
// (the configured group while wanted, the extra groups) or notified affiliated (clause 8.2.1.3). No group left: the
// de-affiliation of step 4 (Expires 0).
bool Client::mcvideo_change_affiliation(const std::vector<std::string> &affiliate, const std::vector<std::string> &leave) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo affiliation requires MCVideo service authorisation");
  std::set<std::string> current(mcvideo_extra_groups_.begin(), mcvideo_extra_groups_.end());
  if (mcvideo_desired_affiliation_ && !mcvideo_left_selected_) current.insert(mcvideo_group());
  const auto affiliated = [&](const std::string &group) {
    return current.contains(group) || mcvideo_groups_.contains(group);
  };
  std::vector<std::string> added, removed;
  for (const auto &group : affiliate)
    if (!affiliated(group) && std::find(added.begin(), added.end(), group) == added.end()) added.push_back(group);
  for (const auto &group : leave)
    if (affiliated(group) && std::find(removed.begin(), removed.end(), group) == removed.end())
      removed.push_back(group);
  event("mcvideo_affiliation_change", {{"affiliate", added}, {"de-affiliate", removed}});
  if (added.empty() && removed.empty()) return false;
  for (const auto &group : removed) {
    current.erase(group);
    if (group == mcvideo_group())
      mcvideo_left_selected_ = true; // left out of every PUBLISH until affiliated again
    else
      mcvideo_extra_groups_.erase(group);
  }
  if (current.empty() && added.empty()) {
    mcvideo_affiliate(false);
    return true;
  }
  mcvideo_publish_affiliation(true, added, added.size() == 1 ? added.front() : std::string{});
  return true;
}

} // namespace mcx
