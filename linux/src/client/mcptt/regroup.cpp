//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Group and user regroup using a preconfigured group: the MCPTT client's requests and the notices it receives.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 16.2.1.3 and clause 16.2.1.4: the MCPTT client receives the notice of creation or removal of a
// regroup using a preconfigured group and stores or forgets it.
void Client::regroup_notified(const Regroup &regroup) {
  // TS 24.379 clause 16.2.1.3 step 3: a notice naming users is a user regroup (clause 16.3.1.3), and
  // step 4: the client is affiliated with the regroup either way.
  if (regroup.action == "create") {
    // The count records repeated notices, such as the copy of TS 24.379 clause 10.1.2.4.1 step
    // 4c-ii sent when a call is attempted on a constituent group.
    const auto seen =
        regroups_.contains(regroup.regroup_uri) ? regroups_.at(regroup.regroup_uri).value("notifications", 0u) : 0u;
    regroups_[regroup.regroup_uri] = {{"preconfigured", regroup.preconfigured_group},
                                      {"groups", regroup.groups},
                                      {"users", regroup.users},
                                      {"notifications", seen + 1}};
  } else {
    regroups_.erase(regroup.regroup_uri);
  }
  status_["regroups"] = regroups_;
  event("regroup_notification", {{"regroup", regroup.regroup_uri},
                                 {"action", regroup.action},
                                 {"groups", regroup.groups},
                                 {"users", regroup.users}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 16.2.1.1, clause 16.3.1.1 and clause 16.2.1.2: ask this user's participating function to
// create a group or user regroup using a preconfigured group, or to remove one (SIP MESSAGE).
void Client::regroup_command(const std::string &action, const std::string &tgi, const std::string &preconfigured,
                             const std::vector<std::string> &groups, const std::vector<std::string> &users) {
  Regroup regroup;
  regroup.action = action;
  regroup.regroup_uri = tgi;
  regroup.preconfigured_group = preconfigured;
  regroup.groups = groups;
  regroup.users = users;
  McpttInfo info;
  info.client_id = client_id();
  const std::vector<BodyPart> parts{{mcptt_mime, mcptt_info_xml(info)}, {regroup_mime, regroup_xml(regroup)}};
  const auto boundary = "mcx-" + random_id();
  regroup_result_ = {{"action", action}, {"regroup", tgi}, {"status", 0}, {"warning", ""}};
  status_["regroup_result"] = regroup_result_;
  sip_.request("MESSAGE", service(), "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               {{"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"},
                {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcptt_icsi) + "\";require;explicit"},
                {"P-Preferred-Service", mcptt_icsi}},
               [this, action, tgi](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 regroup_result_ = {{"action", action},
                                    {"regroup", tgi},
                                    {"status", response.status_code},
                                    {"warning", std::string(get_header(response.headers, "Warning"))}};
                 status_["regroup_result"] = regroup_result_;
                 event("regroup_result", regroup_result_);
               });
}

} // namespace mcx
