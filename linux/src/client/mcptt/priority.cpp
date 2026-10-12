//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Emergency and imminent peril: the priority call conditions and states of the MCPTT client, priority changes in
// group and private calls, emergency alerts and the user profile checks.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 33.180 clause 5.2.8.2.5: the KMS Redirect Response (KRR) a response carries, for KMS selection at the initiator.
std::optional<std::string> Client::krr_body(const SipResponse &response) {
  if (response.content_type.starts_with(keying::krr_mime)) return response.body;
  for (const auto &part : response.parts)
    if (part.content_type == keying::krr_mime) return part.body;
  return std::nullopt;
}

//------------------------------------------------------------------------------
// Helper: client-side authorisation from the user profile (e.g. TS 24.379
// clause 11.1.1.2.1.1 step 2, clause 11.1.5.2.1): refuse locally when enforcement is on.
void Client::require_profile(std::initializer_list<const char *> actions) const {
  if (!cfg_.at("mcptt").value("enforce_profile_ruleset", true) || !profile_loaded_) return;
  for (const auto *action : actions)
    if (!profile_actions_.contains(action))
      throw std::runtime_error(std::string("not authorised by the user profile: ") + action);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.8.1: refuses an MCData request whose rights the MCData user profile (TS 24.484 clause 10.3)
// does not grant, as require_profile does for the MCPTT user profile.
void Client::require_mcdata_profile(std::initializer_list<const char *> actions) const {
  if (!cfg_.at("mcptt").value("enforce_profile_ruleset", true) || !mcdata_profile_loaded_) return;
  for (const auto *action : actions)
    if (!mcdata_profile_actions_.contains(action))
      throw std::runtime_error(std::string("not authorised by the MCData user profile: ") + action);
}

//------------------------------------------------------------------------------
// TS 24.379 annex G.11: the name of an MCPTT emergency private call state (MEPC).
const char *Client::mepc_name(int state) {
  return state == 2   ? "MEPC 2: emergency-pc-requested"
         : state == 3 ? "MEPC 3: emergency-pc-granted"
                      : "MEPC 1: emergency-pc-capable";
}

//------------------------------------------------------------------------------
// TS 24.379 annex G.10: the name of an MCPTT emergency private priority state (MEPP).
const char *Client::mepp_name(int state) {
  return state == 2   ? "MEPP 2: in-progress"
         : state == 3 ? "MEPP 3: cancel-pending"
         : state == 4 ? "MEPP 4: confirm-pending"
                      : "MEPP 1: no-emergency";
}

//------------------------------------------------------------------------------
// TS 24.379 annex G.12: the name of an MCPTT private emergency alert state (MPEA).
const char *Client::mpea_name(int state) {
  return state == 2   ? "MPEA 2: emergency-alert-confirm-pending"
         : state == 3 ? "MPEA 3: emergency-alert-initiated"
         : state == 4 ? "MPEA 4: emergency-alert-cancel-pending"
                      : "MPEA 1: no-alert";
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.1.4 step 1b and clause 6.2.8.3.4 step 3: whether a 2xx carries Warning 149 (SIP INFO request
// pending, clause 4.4.2), which keeps the emergency alert state confirm-pending.
bool Client::info_pending(const SipResponse &response) {
  return client_rules::has_warning(get_header(response.headers, "Warning"), 149);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.3: the private call priority learned from the server (terminating side, the peer
// upgraded or cancelled): the MEPP state (annex G.10) and the private emergency alerts.
void Client::update_private_priority(const McpttInfo &info) {
  if (info.emergency) mepp_ = *info.emergency ? 2 : 1;
  const auto origin = info.originated_by.empty() ? info.calling_user : info.originated_by;
  if (info.alert && !origin.empty()) {
    if (*info.alert)
      private_alerts_.insert(origin);
    else
      private_alerts_.erase(origin);
  }
  event("private_priority_status", {{"mepp", mepp_name(mepp_)}, {"alerts", private_alerts_}});
}

//------------------------------------------------------------------------------
// Helper: the emergency location (TS 24.379 annex F.3 location information) a received SIP request carries.
std::optional<EmergencyLocation> Client::message_location(const SipMessage &message) {
  return parse_emergency_location_parts(message.parts.empty() ? parse_body_parts(message.content_type, message.body)
                                                              : message.parts);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.9.1 step 2: the location included with an emergency alert.
EmergencyLocation Client::configured_emergency_location() const {
  // TS 24.379 clause 6.2.9.1 step 2c: the UE's current coordinate once it has one.
  if (const auto position = location_.last_position()) return {position->latitude, position->longitude};
  const auto &mcptt = cfg_.at("mcptt");
  if (!mcptt.contains("emergency_location"))
    throw std::runtime_error("alert requires explicitly configured emergency_location");
  const auto &input = mcptt.at("emergency_location");
  const auto encoded = [&](const char *key) {
    const auto &value = input.at(key);
    if (!value.is_number_integer() || value.get<std::int64_t>() < 0 || value.get<std::uint64_t>() > 0xffffff)
      throw std::runtime_error("emergency_location coordinates must be encoded unsigned 24-bit integers");
    return value.get<std::uint32_t>();
  };
  return {encoded("latitude"), encoded("longitude")};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.1.13, clause 10.1.1.2.1.6 and clause 12.1.1.3: the group's priority (emergency, imminent
// peril) and the emergency alerts (with their locations) learned from the server.
void Client::update_priority(const McpttInfo &info, std::optional<EmergencyLocation> location) {
  if (info.emergency) {
    emergency_ = *info.emergency;
    imminent_ = false;
  }
  if (info.imminent_peril == true && info.emergency != true) emergency_ = false;
  if (info.imminent_peril && !emergency_) imminent_ = *info.imminent_peril;
  if (info.alert) {
    const auto origin = info.originated_by.empty() ? info.calling_user : info.originated_by;
    if (!origin.empty()) {
      if (*info.alert) {
        alerts_.insert(origin);
        if (location)
          status_["alert_locations"][origin] = {{"latitude", location->latitude}, {"longitude", location->longitude}};
      } else if (info.emergency != true) {
        alerts_.erase(origin);
        if (status_.contains("alert_locations")) status_["alert_locations"].erase(origin);
      }
    }
  }
  status_["priority"] = emergency_ ? "emergency" : imminent_ ? "imminent" : "normal";
  status_["alerts"] = alerts_;
  event("priority_status", {{"priority", status_["priority"]}, {"alerts", alerts_}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.1.4 and clause 6.2.8.1.5: take the priority state from the mcptt-info of a response to a
// priority request.
void Client::priority_response(const SipResponse &response) {
  if (response.body.empty()) return;
  const auto parts = parse_body_parts(response.content_type, response.body);
  const auto location = parse_emergency_location_parts(parts);
  for (const auto &part : parts)
    if (part.content_type == mcptt_mime) update_priority(parse_mcptt_info(part.body), location);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.1.2, clause 6.2.8.1.12 and clause 6.2.8.3.3: the Resource-Priority header field of an
// emergency, imminent peril or normal request, with the values of the MCPTT service configuration document (clause
// 6.2.8.1.15).
std::vector<SipHeader> Client::priority_headers(const McpttInfo &info) const {
  const auto values = service_priorities("mcptt");
  const char *name = info.emergency == true                                    ? "emergency"
                     : info.imminent_peril == true                             ? "imminent"
                     : info.emergency == false || info.imminent_peril == false ? "normal"
                     : emergency_                                              ? "emergency"
                     : imminent_                                               ? "imminent"
                                                                               : "normal";
  const auto value = values.value(name, std::string{});
  if (value.empty()) {
    if (info.emergency || info.imminent_peril)
      throw std::runtime_error("priority Resource-Priority values are not provisioned");
    return {};
  }
  return {{"Resource-Priority", value}};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.5 and clause 11.1.1.2.1.4: upgrade to or cancel an emergency private call in the
// established dialog (re-INVITE of clause 6.2.8.3.2 or clause 6.2.8.3.6, responses per clause 6.2.8.3.4 and
// clause 6.2.8.3.5); with_alert also starts or cancels the alert (MPEA, annex G.12).
void Client::change_private_priority(bool enable, bool with_alert) {
  if (dialog_.empty() || !remote_ || status_.value("call_state", "") != "active")
    throw std::runtime_error("emergency private call change requires an active private call");
  if (priority_pending_) throw std::runtime_error("priority update already pending");
  if (enable && mepp_ == 2) throw std::runtime_error("private call is already an emergency call");
  if (!enable && mepp_ != 2) throw std::runtime_error("private call is not an emergency call");
  McpttInfo info;
  info.session_type = "private";
  info.client_id = client_id();
  info.emergency = enable;
  if (enable)
    info.alert = with_alert;
  else if (with_alert)
    info.alert = false;
  auto parts = info_parts(info);
  if (enable && with_alert)
    parts.push_back({mcptt_location_mime, emergency_location_xml(configured_emergency_location())});
  auto headers = priority_headers(info);
  const int previous_mpea = mpea_;
  if (enable) {
    mepc_ = 2;
    mepp_ = 4;
    if (with_alert) mpea_ = 2;
  } else {
    mepp_ = 3;
    if (with_alert && mpea_ == 3) mpea_ = 4;
  }
  priority_pending_ = true;
  try {
    sip_.reinvite(
        dialog_, local_, parts,
        [this, enable, previous_mpea](const SipResponse &response) {
          if (response.status_code < 200) return;
          priority_pending_ = false;
          status_["priority_updates_completed"] = status_.value("priority_updates_completed", 0u) + 1;
          if (response.status_code < 300) {
            if (enable) {
              mepp_ = 2;
              mepc_ = 3;
              if (mpea_ == 2 && !info_pending(response)) {
                mpea_ = 3;
                private_alerts_.insert(user());
              }
            } else {
              mepp_ = 1;
              mepc_ = 1;
              if (mpea_ == 4 && !info_pending(response)) {
                mpea_ = 1;
                private_alerts_.erase(user());
              }
            }
          } else {
            if (enable) {
              mepc_ = 1;
              if (mepp_ == 4) mepp_ = 1;
              if (mpea_ == 2) mpea_ = 1;
            } else {
              mepp_ = 2;
              mpea_ = previous_mpea;
            }
            failure("emergency private call change: " + std::to_string(response.status_code));
          }
        },
        headers);
  } catch (...) {
    priority_pending_ = false;
    throw;
  }
  event("private_priority_requested", {{"emergency", enable}, {"alert", with_alert}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.1.3 to clause 10.1.1.2.1.5 (prearranged) and clause 10.1.2.2.1.3 to clause 10.1.2.2.1.5
// (chat): upgrade the active group call to emergency or imminent peril, or cancel it (re-INVITE); outside a call,
// cancel the in-progress emergency group state by SIP MESSAGE (clause 12.1.1.5). A private call goes to
// change_private_priority().
void Client::change_priority(bool emergency, bool enabled) {
  if (call_type_ == "private") {
    if (!emergency) throw std::runtime_error("imminent peril does not apply to private calls");
    return change_private_priority(enabled, false);
  }
  // In a broadcast group call only its initiator may change the priority; the server enforces it.
  if (call_type_ == "ambient" && !ambient_hidden())
    throw std::runtime_error("priority changes do not apply to ambient listening calls");
  const bool active = !dialog_.empty() && remote_ && status_.value("call_state", "") == "active";
  if (!active && !(emergency && !enabled && authorized_ && affiliated_))
    throw std::runtime_error("priority change requires active call or affiliated emergency cancellation");
  if (priority_pending_) throw std::runtime_error("priority update already pending");
  McpttInfo info;
  info.session_type = call_type_ == "chat" ? "chat" : "prearranged";
  info.request_uri = group();
  info.client_id = client_id();
  if (emergency) {
    info.emergency = enabled;
    if (enabled) info.alert = false;
  } else
    info.imminent_peril = enabled;
  auto headers = priority_headers(info);
  headers.push_back({"P-Preferred-Service", mcptt_icsi});
  headers.push_back({"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"});
  headers.push_back(
      {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"});
  priority_pending_ = true;
  try {
    auto completed = [this](const SipResponse &response) {
      if (response.status_code < 200) return;
      priority_pending_ = false;
      status_["priority_updates_completed"] = status_.value("priority_updates_completed", 0u) + 1;
      priority_response(response);
      if (response.status_code >= 300) failure("priority update: " + std::to_string(response.status_code));
    };
    if (active)
      sip_.reinvite(dialog_, local_, info_parts(info), completed, headers);
    else
      sip_.request("MESSAGE", service(), mcptt_mime, mcptt_info_xml(info), headers, completed);
  } catch (...) {
    priority_pending_ = false;
    throw;
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 12.1.1.1 and clause 12.1.1.2: originate or cancel an emergency alert (SIP MESSAGE with the
// location of clause 6.2.9.1); origin names the user whose alert is cancelled.
void Client::change_alert(bool enabled, const std::string &origin) {
  // Starting an alert may affiliate implicitly (TS 24.379 clause 9.2.2.3.6); cancelling needs affiliation.
  if (!authorized_ || (!affiliated_ && !enabled)) throw std::runtime_error("alert cancellation requires affiliation");
  if (!alert_pending_.empty()) throw std::runtime_error("alert request already pending");
  McpttInfo info;
  info.request_uri = group();
  info.client_id = client_id();
  info.alert = enabled;
  info.originated_by = origin;
  const auto location = enabled ? std::optional<EmergencyLocation>{configured_emergency_location()} : std::nullopt;
  auto parts = info_parts(info);
  if (location) parts.push_back({mcptt_location_mime, emergency_location_xml(*location)});
  const auto boundary = "mcx-" + random_id();
  alert_pending_ = enabled ? "start" : "cancel";
  const auto [type, body] = message_body(parts, boundary);
  sip_.request(
      "MESSAGE", service(), type, body,
      {{"P-Preferred-Service", mcptt_icsi},
       {"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"},
       {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"}},
      [this, enabled, location](const SipResponse &response) {
        if (response.status_code < 200) return;
        if (response.status_code >= 300) {
          alert_pending_.clear();
          priority_response(response);
          failure("emergency alert: " + std::to_string(response.status_code));
        } else if (enabled) {
          // Start is acknowledged by 2xx; cancellation completes on the receipt MESSAGE.
          alerts_.insert(user());
          alert_pending_.clear();
          if (location)
            status_["alert_locations"][user()] = {{"latitude", location->latitude}, {"longitude", location->longitude}};
        }
      });
}

} // namespace mcx
