//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo functional alias to group binding (TS 24.281 clause 20.4); the remote change of a user's selected group
// (TS 24.281 clause 9.2.4) is shared with MCPTT (core/group_selection.cpp). Member functions of Client (declared in
// client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), TS 24.484 V19.6.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clauses 20.4.2.1.2 (binding) and 20.4.2.1.3 (unbinding): the MCVideo client sends a SIP MESSAGE with
// <request-type> "fa-group-binding-req", the <binding-ind> and the functional alias, and the groups in a
// resource-lists body.
void Client::mcvideo_alias_binding(bool bind, const std::string &alias, const std::vector<std::string> &groups) {
  if (!mcvideo_authorized_) throw std::runtime_error("functional alias binding requires MCVideo service authorisation");
  if (alias.empty() || groups.empty()) throw std::runtime_error("mcvideo-fa-bind|unbind <alias> <group>[,<group>...]");
  if (bind && !mcvideo_aliases_.contains(alias))
    throw std::runtime_error("a functional alias must be activated to be bound");
  McpttInfo info;
  info.client_id = client_id();
  info.functional_alias = mcvideo_presented_alias();
  info.service["request-type"] = "fa-group-binding-req";
  info.service["binding-ind"] = bind ? "true" : "false";
  info.service[bind ? "binding-fa-uri" : "unbinding-fa-uri"] = alias;
  const std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                                    {resource_lists_mime, resource_list_xml(groups)}};
  const auto boundary = "mcx-" + random_id();
  const auto action = bind ? "bind" : "unbind";
  mcvideo_binding_result_ = {{"action", action}, {"alias", alias}, {"groups", groups},
                             {"status", 0},      {"warning", ""},  {"request", ++mcvideo_binding_requests_}};
  sip_.request("MESSAGE", mcvideo_service_uri(), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), mcvideo_call_headers(), [this](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_binding_result_["status"] = response.status_code;
                 mcvideo_binding_result_["warning"] = std::string(get_header(response.headers, "Warning"));
                 event("mcvideo_functional_alias_binding_result", mcvideo_binding_result_);
               });
  event("mcvideo_functional_alias_binding_requested", {{"action", action}, {"alias", alias}, {"groups", groups}});
}

} // namespace mcx
