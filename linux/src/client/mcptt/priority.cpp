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
// TS 24.379 clause 6.2.8 (the authorisation of priority requests from the MCPTT user profile, TS 24.484 clause 8.3.2;
// with mcptt.enforce_profile_ruleset and the profile read): an unauthorised request is refused before anything is
// sent (the command fails: "should indicate to the MCPTT user that they are not authorised"). request:
//   "emergency-group" (clause 6.2.8.1.8: <allow-emergency-group-call> and <MCPTT-group-call>/<EmergencyCall>/
//     <MCPTTGroupInitiation> "DedicatedGroup" naming the group or "UseCurrentlySelectedGroup");
//   "imminent-group" (clause 6.2.8.1.8: <allow-imminent-peril-call> and <ImminentPerilCall>/<MCPTTGroupInitiation>);
//   "imminent-cancel" (clause 6.2.8.1.10: <allow-cancel-imminent-peril>);
//   "alert" (clause 6.2.8.1.6: <allow-activate-emergency-alert> and <EmergencyAlert> "DedicatedGroup" naming the
//     group or "UseCurrentlySelectedGroup"; the group document's <allow-MCPTT-emergency-alert> is the server's);
//   "alert-cancel", "private-alert-cancel" (clauses 6.2.8.1.6, 6.2.8.3.1.3: <allow-cancel-emergency-alert>);
//   "emergency-private" (clause 6.2.8.3.1.1: <allow-emergency-private-call> and <PrivateCall>/<EmergencyCall>/
//     <MCPTTPrivateRecipient> "UsePreConfigured" naming the user or "LocallyDetermined");
//   "emergency-private-cancel" (clause 6.2.8.3.1.2: <allow-cancel-private-emergency-call>);
//   "private-alert" (clause 6.2.8.3.1.3: <allow-activate-emergency-alert> and <OnNetwork>/<PrivateEmergencyAlert>
//     "UsePreConfigured" naming the user or "LocallyDetermined").
void Client::require_priority_right(const std::string &request, const std::string &target) const {
  if (!cfg_.at("mcptt").value("enforce_profile_ruleset", true) || !profile_loaded_) return;
  using client_rules::profile_entry;
  using client_rules::profile_entry_allows;
  const auto deny = [&](const char *right) {
    throw std::runtime_error("not authorised by the user profile: " + request + " (" + right + ")");
  };
  const auto action = [&](const char *name) {
    if (!profile_actions_.contains(name)) deny(name);
  };
  if (request == "emergency-group") {
    action("allow-emergency-group-call");
    if (!profile_entry_allows(profile_entry(profile_body_, {"MCPTT-group-call", "EmergencyCall", "MCPTTGroupInitiation"}),
                              "DedicatedGroup", "UseCurrentlySelectedGroup", target))
      deny("EmergencyCall MCPTTGroupInitiation");
  } else if (request == "imminent-group") {
    action("allow-imminent-peril-call");
    if (!profile_entry_allows(profile_entry(profile_body_, {"ImminentPerilCall", "MCPTTGroupInitiation"}),
                              "DedicatedGroup", "UseCurrentlySelectedGroup", target))
      deny("ImminentPerilCall MCPTTGroupInitiation");
  } else if (request == "imminent-cancel") {
    action("allow-cancel-imminent-peril");
  } else if (request == "alert") {
    action("allow-activate-emergency-alert");
    if (!profile_entry_allows(profile_entry(profile_body_, {"MCPTT-group-call", "EmergencyAlert"}), "DedicatedGroup",
                              "UseCurrentlySelectedGroup", target))
      deny("EmergencyAlert");
  } else if (request == "alert-cancel" || request == "private-alert-cancel") {
    action("allow-cancel-emergency-alert");
  } else if (request == "emergency-private") {
    action("allow-emergency-private-call");
    if (!profile_entry_allows(profile_entry(profile_body_, {"PrivateCall", "EmergencyCall", "MCPTTPrivateRecipient"}),
                              "UsePreConfigured", "LocallyDetermined", target))
      deny("EmergencyCall MCPTTPrivateRecipient");
  } else if (request == "emergency-private-cancel") {
    action("allow-cancel-private-emergency-call");
  } else if (request == "private-alert") {
    action("allow-activate-emergency-alert");
    if (!profile_entry_allows(profile_entry(profile_body_, {"OnNetwork", "PrivateEmergencyAlert"}), "UsePreConfigured",
                              "LocallyDetermined", target))
      deny("PrivateEmergencyAlert");
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.3.2 3): the emergency alert of an emergency private call is sent only when it "is an authorised
// request for MCPTT emergency alert as determined by the procedures of clause 6.2.8.3.1.3"; otherwise the call goes
// on as an emergency private call without the alert (no <alert-ind> "true", no MPEA change, no location). False, with
// the user told (status emergency_alert_not_authorised and an event), when the alert to target is not authorised.
bool Client::private_alert_authorised(const std::string &target) {
  try {
    require_priority_right("private-alert", target);
    return true;
  } catch (const std::runtime_error &error) {
    status_["emergency_alert_not_authorised"] = target;
    event("emergency_alert_not_authorised", {{"target", target}, {"reason", error.what()}});
    return false;
  }
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
// TS 24.379 clause 6.2.8.1.13, clause 10.1.1.2.1.6 and clause 12.1.1.3: a group's priority (emergency, imminent
// peril) and the emergency alerts (with their locations) learned from the server, applied to the group's state
// {"priority", "alerts", "alert_locations"}.
void Client::priority_into(Json &state, const McpttInfo &info, std::optional<EmergencyLocation> location) {
  if (!state.is_object()) state = Json::object(); // a monitored group's first notice
  const auto priority = state.value("priority", std::string{"normal"});
  bool emergency = priority == "emergency", imminent = priority == "imminent";
  auto alerts = state.value("alerts", std::set<std::string>{});
  auto locations = state.value("alert_locations", Json::object());
  if (info.emergency) {
    emergency = *info.emergency;
    imminent = false;
  }
  if (info.imminent_peril == true && info.emergency != true) emergency = false;
  if (info.imminent_peril && !emergency) imminent = *info.imminent_peril;
  if (info.alert) {
    const auto origin = info.originated_by.empty() ? info.calling_user : info.originated_by;
    if (!origin.empty()) {
      if (*info.alert) {
        alerts.insert(origin);
        if (location) locations[origin] = {{"latitude", location->latitude}, {"longitude", location->longitude}};
      } else if (info.emergency != true) {
        alerts.erase(origin);
        locations.erase(origin);
      }
    }
  }
  state = {{"priority", emergency  ? "emergency"
                        : imminent ? "imminent"
                                   : "normal"},
           {"alerts", alerts},
           {"alert_locations", locations}};
}

//------------------------------------------------------------------------------
// Helper: the live priority state (the selected group's, or the call's on a monitored group), as priority_into
// takes it, and back.
Json Client::live_priority() const {
  return {{"priority", emergency_  ? "emergency"
                       : imminent_ ? "imminent"
                                   : "normal"},
          {"alerts", alerts_},
          {"alert_locations", status_.value("alert_locations", Json::object())}};
}

void Client::set_live_priority(const Json &state) {
  emergency_ = state.at("priority") == "emergency";
  imminent_ = state.at("priority") == "imminent";
  alerts_ = state.at("alerts").get<std::set<std::string>>();
  status_["alert_locations"] = state.at("alert_locations");
  status_["priority"] = state.at("priority");
  status_["alerts"] = alerts_;
}

//------------------------------------------------------------------------------
// The live group's priority and alerts updated from the server (see priority_into).
void Client::update_priority(const McpttInfo &info, std::optional<EmergencyLocation> location) {
  auto state = live_priority();
  priority_into(state, info, location);
  set_live_priority(state);
  event("priority_status", {{"priority", status_["priority"]}, {"alerts", alerts_}});
}

//------------------------------------------------------------------------------
// Helper: a call on a monitored group takes that group's priority and alerts as the live state, the selected group's
// waiting until the call ends; at its end the call's state goes back to the monitored group.
void Client::enter_monitored_call(const std::string &monitored) {
  if (selected_priority_) return;
  selected_priority_ = live_priority();
  set_live_priority(monitored_state_.value(
      monitored, Json{{"priority", "normal"}, {"alerts", Json::array()}, {"alert_locations", Json::object()}}));
}

void Client::leave_monitored_call() {
  if (!selected_priority_) return;
  if (monitoring(call_group_)) monitored_state_[call_group_] = live_priority();
  set_live_priority(*selected_priority_);
  selected_priority_.reset();
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
void Client::change_private_priority(bool enable, bool with_alert, const std::string &other_alert) {
  const bool third_party = !enable && (!other_alert.empty() || (mepp_ != 2 && private_emergency_offered_));
  if (dialog_.empty() || !remote_ || status_.value("call_state", "") != "active")
    throw std::runtime_error("emergency private call change requires an active private call");
  if (priority_pending_) throw std::runtime_error("priority update already pending");
  if (enable && mepp_ == 2) throw std::runtime_error("private call is already an emergency call");
  if (!enable && mepp_ != 2 && !third_party) throw std::runtime_error("private call is not an emergency call");
  // TS 24.379 clauses 6.2.8.3.1.1 to 6.2.8.3.1.3 (clause 11.1.1.2.1.5 1), clause 11.1.1.2.1.4 1)): the user's rights.
  require_priority_right(enable ? "emergency-private" : "emergency-private-cancel", private_peer_);
  // TS 24.379 clause 11.1.1.2.1.5 with clause 6.2.8.3.2 3): an unauthorised alert leaves the upgrade without it.
  if (with_alert && enable) with_alert = private_alert_authorised(private_peer_);
  if (with_alert && !enable) require_priority_right("private-alert-cancel", private_peer_);
  McpttInfo info;
  info.session_type = "private";
  info.client_id = client_id();
  info.emergency = enable;
  if (enable)
    info.alert = with_alert;
  else if (with_alert)
    info.alert = false;
  // TS 24.379 clause 6.2.8.3.8 3) b): the cancel of another user's alert names that user.
  if (!enable && with_alert && !other_alert.empty()) info.originated_by = other_alert;
  auto parts = info_parts(info);
  if (enable && with_alert)
    parts.push_back({mcptt_location_mime, emergency_location_xml(configured_emergency_location())});
  auto headers = priority_headers(info);
  // TS 24.379 clause 11.1.1.2.1.5 4) and clause 6.4: the upgrade may be an implicit floor request.
  const bool implicit = enable && upgrade_implicit_begin();
  const int previous_mpea = mpea_;
  if (!third_party) emergency_state_ = enable; // TS 24.379 annex G.1 (clauses 6.2.8.3.2, 6.2.8.3.6)
  if (enable) {
    mepc_ = 2;
    mepp_ = 4;
    if (with_alert) mpea_ = 2;
  } else {
    mepp_ = 3; // clause 6.2.8.3.6 2), clause 6.2.8.3.8 2): "MEPP 3: cancel-pending"
    if (with_alert && mpea_ == 3 && other_alert.empty()) mpea_ = 4;
  }
  priority_pending_ = true;
  try {
    sip_.reinvite(
        dialog_, local_, parts,
        [this, enable, previous_mpea, implicit](const SipResponse &response) {
          if (response.status_code < 200) return;
          if (implicit) schedule([this, code = response.status_code] { upgrade_implicit_answered(code); });
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
    if (implicit) upgrade_implicit_answered(500);
    local_.implicit_request = false;
    throw;
  }
  local_.implicit_request = false;
  event("private_priority_requested", {{"emergency", enable}, {"alert", with_alert}, {"implicit", implicit}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.1.3 to clause 10.1.1.2.1.5 (prearranged) and clause 10.1.2.2.1.3 to clause 10.1.2.2.1.5
// (chat): upgrade the active group call to emergency or imminent peril, or cancel it (re-INVITE); outside a call,
// cancel the in-progress emergency group state by SIP MESSAGE (clause 12.1.1.5). A private call goes to
// change_private_priority().
void Client::change_priority(bool emergency, bool enabled, const std::string &other_alert, bool third_party) {
  if (call_type_ == "private") {
    if (!emergency) throw std::runtime_error("imminent peril does not apply to private calls");
    return change_private_priority(enabled, false);
  }
  // In a broadcast group call only its initiator may change the priority; the server enforces it.
  if (call_type_ == "ambient" && !ambient_hidden())
    throw std::runtime_error("priority changes do not apply to ambient listening calls");
  const bool active = !dialog_.empty() && remote_ && status_.value("call_state", "") == "active";
  // TS 24.379 clause 10.1.6.2.1: the in-progress imminent peril state of the group cancelled with no call ongoing.
  if (!active && !emergency && !enabled) return imminent_state_cancel(group());
  if (!active && !(emergency && !enabled && authorized_ && affiliated_))
    throw std::runtime_error("priority change requires active call or affiliated emergency cancellation");
  if (priority_pending_) throw std::runtime_error("priority update already pending");
  // TS 24.379 clauses 10.1.1.2.1.3 1), 2) (6.2.8.1.8) and 10.1.1.2.1.5 1) b) (6.2.8.1.10): the user's rights; the
  // cancel of the emergency state is local policy (clause 6.2.8.1.7).
  const auto target_group = active && selected_priority_ ? call_group_ : active ? active_group() : group();
  if (enabled) require_priority_right(emergency ? "emergency-group" : "imminent-group", target_group);
  if (!enabled && !emergency) require_priority_right("imminent-cancel", target_group);
  McpttInfo info;
  info.session_type = call_type_ == "chat" ? "chat" : "prearranged";
  // A call on a monitored group changes that group's priority.
  info.request_uri = active && selected_priority_ ? call_group_ : group();
  info.client_id = client_id();
  if (emergency) {
    info.emergency = enabled;
    if (enabled) info.alert = false;
    // TS 24.379 clause 6.2.8.1.1 1) (set), clause 6.2.8.1.3 2) (cleared); a third party's cancel (clause 6.2.8.1.14)
    // leaves this user's own state.
    if (!third_party) emergency_state_ = enabled;
    // TS 24.379 clause 6.2.8.1.14 3): with the cancel of another user's alert, <alert-ind> false and <originated-by>.
    if (!enabled && !other_alert.empty()) {
      require_priority_right("alert-cancel", target_group);
      info.alert = false;
      info.originated_by = other_alert;
    }
  } else
    info.imminent_peril = enabled;
  auto headers = priority_headers(info);
  headers.push_back({"P-Preferred-Service", mcptt_icsi});
  headers.push_back({"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"});
  headers.push_back(
      {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"});
  priority_pending_ = true;
  try {
    auto completed = [this, active, emergency, enabled](const SipResponse &response) {
      if (response.status_code < 200) return;
      priority_pending_ = false;
      status_["priority_updates_completed"] = status_.value("priority_updates_completed", 0u) + 1;
      // TS 24.379 clause 6.2.8.1.4: a 2xx to the upgrade of the call: 1) the emergency group state "MEG 2:
      // in-progress" (and no imminent peril), or 2) without warning 149 the imminent peril group state "MIG 2:
      // in-progress"; the call is then an emergency or imminent peril call, which its Floor Requests say (TS 24.380
      // clause 6.2.4.3.5 1.b, the Floor Indicator). The mcptt-info of the response, if any, follows
      // (priority_response).
      if (active && enabled && response.status_code < 300 &&
          (emergency || get_header(response.headers, "Warning").find("\"149 ") == std::string_view::npos)) {
        McpttInfo upgraded;
        if (emergency)
          upgraded.emergency = true;
        else
          upgraded.imminent_peril = true;
        update_priority(upgraded, std::nullopt);
        if (!emergency) imminent_group_states_[active_group()] = "MIG 2: in-progress";
      }
      priority_response(response);
      if (response.status_code >= 300) failure("priority update: " + std::to_string(response.status_code));
    };
    if (active) {
      // TS 24.379 clause 6.4: an upgrade (not a cancellation) may be an implicit floor request.
      const bool implicit = enabled && upgrade_implicit_begin();
      auto done = implicit ? std::function<void(const SipResponse &)>([this, completed](const SipResponse &response) {
        completed(response);
        if (response.status_code >= 200)
          schedule([this, code = response.status_code] { upgrade_implicit_answered(code); });
      })
                           : std::function<void(const SipResponse &)>(completed);
      try {
        sip_.reinvite(dialog_, local_, info_parts(info), done, headers);
      } catch (...) {
        if (implicit) upgrade_implicit_answered(500);
        local_.implicit_request = false;
        throw;
      }
      local_.implicit_request = false;
    } else
      sip_.request("MESSAGE", service(), mcptt_mime, mcptt_info_xml(info), headers, completed);
  } catch (...) {
    priority_pending_ = false;
    throw;
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.1.4 3) with clause 6.2.8.1.14 (group call) and clause 11.1.1.2.1.4 with clause 6.2.8.3.8
// (private call): "emergency cancel-other [alert [<originator>]]" cancels, in the call, the emergency another user
// started: a re-INVITE with <emergency-ind> false (MEG 3 / MEPP 3 cancel-pending) and, with alert, <alert-ind> false
// and <originated-by> the user whose alert is cancelled (by default the one other user with an alert). The emergency
// group state cancel is local policy (clause 6.2.8.1.7); the alert cancel needs <allow-cancel-emergency-alert>.
void Client::third_party_cancel(bool with_alert, std::string originator) {
  if (dialog_.empty() || !remote_ || status_.value("call_state", "") != "active")
    throw std::runtime_error("a third-party emergency cancel needs a call in progress");
  if (with_alert && originator.empty()) {
    const auto &alerts = call_type_ == "private" ? private_alerts_ : alerts_;
    for (const auto &origin : alerts)
      if (origin != user()) {
        if (!originator.empty()) throw std::runtime_error("several alerts: name the originator");
        originator = origin;
      }
    if (originator.empty()) originator = call_type_ == "private" ? private_peer_ : std::string{};
    if (originator.empty()) throw std::runtime_error("no other user's emergency alert to cancel");
  }
  if (call_type_ == "private") {
    if (mepp_ != 2 && !private_emergency_offered_) throw std::runtime_error("private call is not an emergency call");
    return change_private_priority(false, with_alert, with_alert ? originator : private_peer_);
  }
  change_priority(true, false, with_alert ? originator : std::string{}, true);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.6.2.1: "imminent off [<group>]" with no call ongoing on the group: a SIP MESSAGE (out of
// dialog) to the participating function cancelling the group's in-progress imminent peril state:
//   1) the user's authorisation (clause 6.2.8.1.10, <allow-cancel-imminent-peril>); unauthorised, the user is told
//      (the command fails) and nothing is sent;
//   2), 3) P-Preferred-Service and Accept-Contact with the MCPTT ICSI;
//   5) the mcptt-info body with a) the group, b) <imminentperil-ind> "false", c) the client ID and d) the functional
//      alias in use;
//   6) to the PSI of the participating function; 7) the state "MIG 3: cancel-pending".
// A 2xx sets "MIG 1: no-imminent-peril", a 4xx, 5xx or 6xx "MIG 2: in-progress".
void Client::imminent_state_cancel(const std::string &target) {
  if (!authorized_) throw std::runtime_error("imminent peril state cancellation requires MCPTT service authorisation");
  const auto group = target.empty() ? this->group() : target;
  if (!dialog_.empty() && status_.value("call_state", "") == "active" && active_group() == group)
    throw std::runtime_error("a call is ongoing on the group: its imminent peril is cancelled in the call");
  require_priority_right("imminent-cancel", group); // step 1
  if (priority_pending_) throw std::runtime_error("priority update already pending");
  McpttInfo info;
  info.request_uri = group;                   // step 5 a)
  info.imminent_peril = false;                // step 5 b)
  info.client_id = client_id();               // step 5 c)
  info.functional_alias = functional_alias_;  // step 5 d)
  auto headers = priority_headers(info);      // clause 6.2.8.1.12: Resource-Priority, when provisioned
  for (auto &header : service_message_headers(mcptt_service)) headers.push_back(std::move(header)); // steps 2, 3
  imminent_group_states_[group] = "MIG 3: cancel-pending"; // step 7
  priority_pending_ = true;
  try {
    sip_.request("MESSAGE", service(), mcptt_mime, mcptt_info_xml(info), headers, // steps 6, 8
                 [this, group](const SipResponse &response) {
                   if (response.status_code < 200) return;
                   priority_pending_ = false;
                   const bool ok = response.status_code < 300;
                   imminent_group_states_[group] = ok ? "MIG 1: no-imminent-peril" : "MIG 2: in-progress";
                   if (ok) {
                     // The group's priority state follows: no imminent peril (the live state or a monitored group's).
                     McpttInfo cancelled;
                     cancelled.imminent_peril = false;
                     if (group == this->group() && !selected_priority_)
                       update_priority(cancelled, std::nullopt);
                     else if (monitoring(group))
                       priority_into(monitored_state_[group], cancelled, std::nullopt);
                   } else {
                     failure("imminent peril state cancel: " + std::to_string(response.status_code));
                   }
                   status_["imminent_cancel_result"] = {
                       {"group", group},
                       {"status", response.status_code},
                       {"warning", std::string(get_header(response.headers, "Warning"))},
                       {"state", imminent_group_states_[group]}};
                   event("imminent_cancel_result", status_["imminent_cancel_result"]);
                 });
  } catch (...) {
    priority_pending_ = false;
    imminent_group_states_[group] = "MIG 2: in-progress";
    throw;
  }
  event("imminent_cancel_requested", {{"group", group}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 12.1.1.1 and clause 12.1.1.2: originate or cancel an emergency alert (SIP MESSAGE with the
// location of clause 6.2.9.1); origin names the user whose alert is cancelled. The cancellation names the group of
// the alert (clause 12.1.1.2 step 4a): shown_group when given (the group it was shown for, the selected group or a
// monitored one); else another user's alert during a call on a monitored group is that group's, the user's own alert
// the selected group's.
void Client::change_alert(bool enabled, const std::string &origin, const std::string &shown_group) {
  // Starting an alert may affiliate implicitly (TS 24.379 clause 9.2.2.3.6); cancelling needs affiliation.
  if (!authorized_ || (!affiliated_ && !enabled)) throw std::runtime_error("alert cancellation requires affiliation");
  if (!alert_pending_.empty()) throw std::runtime_error("alert request already pending");
  McpttInfo info;
  const bool monitored_call = selected_priority_.has_value();
  const bool shown_for_call =
      !enabled && monitored_call && !origin.empty() && origin != user() && alerts_.contains(origin);
  if (!shown_group.empty() && shown_group != group() && !monitoring(shown_group))
    throw std::runtime_error("alert cancellation: the group is neither the selected nor a monitored one");
  info.request_uri = !shown_group.empty() ? shown_group : shown_for_call ? call_group_ : group();
  // TS 24.379 clauses 12.1.1.1 1) and 12.1.1.2 1) (clause 6.2.8.1.6): the user's rights.
  require_priority_right(enabled ? "alert" : "alert-cancel", info.request_uri);
  info.client_id = client_id();
  info.alert = enabled;
  info.originated_by = origin;
  const auto location = enabled ? std::optional<EmergencyLocation>{configured_emergency_location()} : std::nullopt;
  auto parts = info_parts(info);
  if (location) parts.push_back({mcptt_location_mime, emergency_location_xml(*location)});
  const auto boundary = "mcx-" + random_id();
  alert_pending_ = enabled ? "start" : "cancel";
  // TS 24.379 annex G.1: an emergency alert sets the MCPTT emergency state; the user's own cancellation clears it.
  if (enabled)
    emergency_state_ = true;
  else if (origin.empty() || origin == user())
    emergency_state_ = false;
  alert_pending_group_ = info.request_uri;
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

//------------------------------------------------------------------------------
// TS 24.379 clause 18.1.1.1: "remote-floor <user> [<group>]": a remote emergency floor request trigger to the user
// who initiated an ongoing emergency group call (on <group>) or emergency private call (no group):
//   1) without <allow-trigger-remote-floor-request> in the user profile, the user is told (the command fails) and
//      nothing is sent;
//   2) a SIP MESSAGE: a), b) P-Preferred-Service and Accept-Contact with the MCPTT ICSI; d) the mcptt-info with i)
//      <mcptt-request-uri> the group (a group call only), ii) A) <req-type> "emergency-remote-floor-request-trigger",
//      B) the functional alias in use; e) the remote user in a resource-lists body; f) to the PSI of the
//      participating function; 3) sent.
// The 200 (OK) is the success of the transmission, a 4xx-6xx its failure (status remote_floor.sent).
void Client::remote_floor_command(const std::string &target, const std::string &group) {
  if (!authorized_) throw std::runtime_error("remote floor request trigger requires MCPTT service authorisation");
  if (cfg_.at("mcptt").value("enforce_profile_ruleset", true) && profile_loaded_ &&
      !profile_actions_.contains("allow-trigger-remote-floor-request"))
    throw std::runtime_error("not authorised to trigger a remote emergency floor request");
  McpttInfo info;
  if (!group.empty()) info.request_uri = group;                       // 2) d) i)
  info.service = {{"req-type", "emergency-remote-floor-request-trigger"}}; // 2) d) ii) A)
  info.functional_alias = functional_alias_;                             // 2) d) ii) B)
  const auto request = remote_floor_["sent"].is_object() ? remote_floor_["sent"].value("request", 0u) + 1 : 1u;
  remote_floor_["sent"] = {{"target", target}, {"group", group}, {"status", 0},
                           {"warning", ""},    {"outcome", ""},  {"request", request}};
  send_service_message(target, info, [this, request](const SipResponse &response) {
    auto &sent = remote_floor_["sent"];
    if (!sent.is_object() || sent.value("request", 0u) != request) return;
    sent["status"] = response.status_code;
    sent["warning"] = std::string(get_header(response.headers, "Warning"));
    sent["outcome"] = response.status_code < 300 ? "success" : "fail";
    event("remote_floor_result", sent);
  });
  event("remote_floor_requested", {{"target", target}, {"group", group}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 18.1.1.2: the "SIP MESSAGE request for emergency remote floor request trigger for terminating
// client":
//   1) a group call with <mcptt-calling-group-id>, else a private call;
//   2) a group call with no ongoing emergency group call on that group: 403, warning 197;
//   3) a private call with no ongoing emergency private call with <mcptt-calling-user-id>: 403, warning 198;
//   4) the MCPTT emergency state (annex G.1) not set: 403, warning 199;
//   5) 200 (OK); 6) a Floor Request for the targeted call (TS 24.380 clause 6.2.4.3.5, the PTT of the user) at the
//      highest floor priority negotiated for it (the emergency priority), when the client neither holds nor asks for
//      the floor already.
SipResponse Client::remote_floor_received(const SipMessage &message, const McpttInfo &info) {
  SipResponse response;
  response.status_code = 200;
  if (message.from_uri != service() || asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  const auto refuse = [&](const char *warning) {
    response.status_code = 403;
    response.headers = {{"Warning", std::string("399 mcx \"") + warning + "\""}};
    remote_floor_["received"] = {{"from", info.calling_user}, {"group", info.calling_group},
                                 {"status", 403},             {"warning", warning},
                                 {"floor_requested", false}};
    event("remote_floor_received", remote_floor_["received"]);
    return response;
  };
  const bool group_call = !info.calling_group.empty();
  const bool active = !dialog_.empty() && remote_ && status_.value("call_state", "") == "active";
  if (group_call &&
      !(active && call_type_ != "private" && call_type_ != "ambient" && active_group() == info.calling_group &&
        (call_type_ == "adhoc" ? adhoc_priority_ == "emergency" : emergency_)))
    return refuse("197 targeted emergency group call is not ongoing");
  if (!group_call && !(active && call_type_ == "private" && private_peer_ == info.calling_user && mepp_ == 2))
    return refuse("198 targeted emergency private call is not ongoing");
  if (!emergency_state_) return refuse("199 remote user is not in emergency state");
  const bool request = !no_floor() && !talking_ && !requesting_;
  remote_floor_["received"] = {{"from", info.calling_user}, {"group", info.calling_group}, {"status", 200},
                               {"warning", ""},             {"floor_requested", request}};
  event("remote_floor_received", remote_floor_["received"]);
  if (request)
    schedule([this] {
      if (!remote_ || talking_ || requesting_ || no_floor()) return;
      next_floor_priority_ = 255; // capped by the negotiated priority (floor_message): the highest allowed
      requesting_ = true;
      // What the user then says: the configured mcptt.remote_floor_wav, else silence for mcptt.remote_floor_hold_ms
      // (5 s), after which the floor is released as at the end of any transmission.
      const auto wav = cfg_.at("mcptt").value("remote_floor_wav", std::string{});
      transmit_ = wav.empty() ? std::vector<std::int16_t>(
                                    16 * cfg_.at("mcptt").value("remote_floor_hold_ms", std::size_t{5000}), 0)
                              : read_wav(wav);
      transmit_offset_ = 0;
      try {
        send_floor(FloorType::Request);
      } catch (const std::exception &error) {
        requesting_ = false;
        failure(std::string("remote emergency floor request: ") + error.what());
      }
    });
  return response;
}

} // namespace mcx
