//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo calls: priority conditions, emergency alerts, incoming MESSAGEs, private calls and calls to the server
// (TS 24.281 clauses 6.2.8, 10.2, 11.2.1, 12.2.2, 13.2.2). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), TS 24.581 V19.2.0 (Rel-19), RFC 4412, RFC 8101.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.8.1.15 (used by clauses 6.2.8.1.2, 6.2.8.1.12 and 6.2.8.3.3): the Resource-Priority header
// field of an emergency or imminent peril call, from the MCVideo service configuration document (RFC 4412 with
// RFC 8101).
std::vector<SipHeader> Client::mcvideo_priority_headers(const McpttInfo &info) const {
  const auto values = service_priorities("mcvideo");
  const char *name = info.emergency == true ? "emergency" : info.imminent_peril == true ? "imminent" : "normal";
  const auto value = values.value(name, std::string{});
  if (value.empty()) {
    if (info.emergency == true || info.imminent_peril == true)
      throw std::runtime_error("MCVideo Resource-Priority values are not provisioned");
    return {};
  }
  return {{"Resource-Priority", value}};
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 6.2.8.1.6 (emergency alert), 6.2.8.1.7 (cancelling an in-progress group emergency), 6.2.8.1.8
// (emergency and imminent peril group calls), 6.2.8.1.10 (cancelling an imminent peril group call), 6.2.8.1.22
// (priority ad hoc group calls), 6.2.8.1.27 (ad hoc group emergency alert) and 6.2.8.3.1 (emergency private call,
// its cancellation, emergency alert to a user): a request is authorised only when the element of the <ruleset> of the
// MCVideo user profile (TS 24.484 clause 9.3.2) is "true"; "In all other cases" (also without a profile) it is an
// unauthorised request, which is not sent. The conditions on the request's target (the <MCVideoGroupInitiation>
// entries of clause 6.2.8.1.8 a) and b) and the like) are require_mcvideo_target's.
void Client::require_mcvideo_profile(const char *action, const char *what) {
  if (mcvideo_profile_allows(action)) return;
  status_["mcvideo_not_authorised"] = {{"action", action}, {"request", what}};
  event("mcvideo_not_authorised", {{"action", action}, {"request", what}});
  throw std::runtime_error(std::string("not authorised by the MCVideo user profile: ") + what + " (" + action + ")");
}

//------------------------------------------------------------------------------
// The target conditions of the authorisations, from the profile's target entries (TS 24.484 clause 9.3.2.3
// EntryType: "entry-info" and <uri-entry>):
//   - clause 6.2.8.1.8 1) and the imminent peril 1): the <entry> of <MCVideoGroupInitiation> of <EmergencyCall> /
//     <ImminentPerilCall> of <MCVideo-group-call>: a) "DedicatedGroup" with <uri-entry> the targeted group, or b)
//     "UseCurrentlySelectedGroup";
//   - clause 6.2.8.1.6 2): the <entry> of <EmergencyAlert> of <MCVideo-group-call>: a) "DedicatedGroup" with
//     <uri-entry> the targeted group, or b) "UseCurrentlySelectedGroup" and the <mcvideo-allow-emergency-alert> action
//     of a <rule> of the targeted group's document (TS 24.481 clause 7.2.2) "true" for this user;
//   - clause 6.2.8.3.1.1 1): the <entry> of <MCVideoPrivateRecipient> of <EmergencyCall> of <PrivateCall>: a)
//     "UsePreConfigured" with <uri-entry> the targeted user, or b) "LocallyDetermined".
// "In all other cases" the request is unauthorised (also without the entry), with one exception: a profile without
// <MCVideoPrivateRecipient> (optional in the schema of TS 24.484 clause 9.3.2.3, <EmergencyCall> minOccurs 0, and
// left out by profiles whose emergency recipients are not one ProSe-addressed user) leaves the emergency private call
// to the ruleset alone.
void Client::require_mcvideo_target(const char *entry, const std::string &target, const char *what) {
  const auto targets = mcvideo_profile_.value("targets", Json::object());
  const bool present = targets.contains(entry);
  const auto info = present ? targets.at(entry).value("entry_info", std::string{}) : std::string{};
  const auto uri = present ? targets.at(entry).value("uri", std::string{}) : std::string{};
  const std::string name = entry;
  bool allowed = false;
  if (name == "emergency_private_recipient") {
    allowed = !present || info == "LocallyDetermined" || (info == "UsePreConfigured" && uri == target);
  } else if (info == "DedicatedGroup") {
    allowed = !target.empty() && uri == target;
  } else if (info == "UseCurrentlySelectedGroup") {
    allowed = true;
    if (name == "group_emergency_alert") {
      // Clause 6.2.8.1.6 2) b): the group document of the group targeted for the alert.
      const auto document = http_.request("GET", groups_xcap() + "/global/byGroupID/" + xcap_segment(target), {},
                                          {{"Authorization", bearer()}});
      try {
        allowed = document.status == 200 &&
                  group_document_allows(document.body, "mcvideo-allow-emergency-alert", {mcvideo_user()});
      } catch (const std::exception &) {
        allowed = false;
      }
    }
  }
  if (allowed) return;
  const Json detail = {{"entry", entry}, {"entry_info", info}, {"uri", uri}, {"target", target}};
  status_["mcvideo_not_authorised"] = {{"action", entry}, {"request", what}, {"target", detail}};
  event("mcvideo_not_authorised", {{"action", entry}, {"request", what}, {"target", detail}});
  throw std::runtime_error(std::string("not authorised by the MCVideo user profile: ") + what + " to " + target +
                           " (" + entry + " " + (present ? info : "absent") + ")");
}

//------------------------------------------------------------------------------
// Helper: record the call's priority (emergency, imminent peril or normal) from the <emergency-ind> and
// <imminentperil-ind> of a received mcvideo-info body.
void Client::mcvideo_note_priority(const McpttInfo &info) {
  if (!mcvideo_call_) return;
  auto &call = *mcvideo_call_;
  // TS 24.281 clause 22.2.2.1.2 steps 4 and 5: an ad hoc group call's indications are <adhoc-emergency-ind> and
  // <imminentperil-ind>, with the ad hoc group states of annex G.14 to G.18.
  if (call.type == "adhoc") {
    if (info.broadcast == true) call.broadcast = true;
    mcvideo_adhoc_priority(info);
    return;
  }
  // TS 24.281 clause 9.2.1.2.1.2 steps 4 c) and 5 b): the group's imminent peril state follows the indications.
  if (call.type != "private") {
    if (info.imminent_peril == true) mcvideo_set_imminent_state(call.group, 2);
    if (info.emergency == true || info.imminent_peril == false) mcvideo_set_imminent_state(call.group, 1);
  }
  if (info.broadcast == true) call.broadcast = true;
  if (info.emergency == true)
    call.priority = "emergency";
  else if (info.imminent_peril == true)
    call.priority = "imminent";
  else if (info.emergency == false || info.imminent_peril == false)
    call.priority = "normal";
}

//------------------------------------------------------------------------------
// TS 24.281 clause 22.2.2.1.2 (and clause 22.2.2.2.2: a re-INVITE in the call is handled as the INVITE): the
// emergency and imminent peril indications of an invitation to an ad hoc group call, received in the
// application/vnd.3gpp.mcvideo-info+xml body: <adhoc-emergency-ind> and <imminentperil-ind> (the definitions 1) and 2)
// at the top of the clause).
void Client::mcvideo_adhoc_priority(const McpttInfo &info) {
  auto &call = *mcvideo_call_;
  if (call.group.empty()) return;
  auto &states = mcvideo_adhoc_groups_[call.group];
  if (!states.contains("emergency")) {
    // Annex G.14, G.15, G.17, G.18: the initial states, before any call activity.
    states = {{"emergency", "MVEAG 1: no-emergency"},
              {"emergency_call", "MVEAGC 1: emergency-gc-capable"},
              {"imminent", "MVIAG 1: no-imminent-peril"},
              {"imminent_call", "MVIAGC 1: imminent-peril-gc-capable"}};
  }
  const bool was_emergency = states["emergency"] == "MVEAG 2: in-progress";
  const bool was_imminent = states["imminent"] == "MVIAG 2: in-progress";
  if (info.adhoc_emergency == true) {
    // Step 4: a) the user is shown that this is an emergency ad hoc group call; b) "MVEAG 2: in-progress"; c)
    // "MVIAGC 1: imminent-peril-capable" and "MVIAG 1: no-imminent-peril" (the emergency supersedes imminent peril).
    states["emergency"] = "MVEAG 2: in-progress";
    states["imminent_call"] = "MVIAGC 1: imminent-peril-gc-capable";
    states["imminent"] = "MVIAG 1: no-imminent-peril";
    call.priority = "emergency";
    if (!was_emergency)
      event("mcvideo_adhoc_emergency_call",
            {{"group", call.group}, {"from", info.calling_user}, {"functional_alias", info.functional_alias}});
  } else if (info.adhoc_emergency == false && was_emergency) {
    // No clause of TS 24.281 sends a terminating client <adhoc-emergency-ind> "false"; taken as the controlling
    // function's in-progress emergency adhoc group state "false" (annex G.13), whose client view is
    // "MVEAG 1: no-emergency" (annex G.14).
    states["emergency"] = "MVEAG 1: no-emergency";
    if (call.priority == "emergency") call.priority = "normal";
    event("mcvideo_adhoc_emergency_cancelled", {{"group", call.group}});
  }
  if (info.imminent_peril == true && info.adhoc_emergency != true && states["emergency"] != "MVEAG 2: in-progress") {
    // Step 5: a) the user is shown that this is an imminent peril ad hoc group call; b) "MVIAG 2: in-progress".
    states["imminent"] = "MVIAG 2: in-progress";
    call.priority = "imminent";
    if (!was_imminent)
      event("mcvideo_adhoc_imminent_peril_call",
            {{"group", call.group}, {"from", info.calling_user}, {"functional_alias", info.functional_alias}});
  } else if (info.imminent_peril == false && was_imminent) {
    // <imminentperil-ind> "false" (TS 24.281 clause 22.4.2.1.1 step 6 c) sends it with the emergency): the ad hoc
    // group's imminent peril state is no longer in progress (annex G.16 "false", G.17 "MVIAG 1").
    states["imminent"] = "MVIAG 1: no-imminent-peril";
    if (call.priority == "imminent") call.priority = "normal";
    event("mcvideo_adhoc_imminent_peril_cancelled", {{"group", call.group}});
  }
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.8.1.25 (with annex G.14 and G.17: "Emergency adhoc group call release request received from the
// MCVideo server", "Imminent peril adhoc group call release request received from the MCVideo server"): on the release
// of a priority ad hoc group call the group's emergency and imminent peril states and this client's call states return
// to "MVEAG 1: no-emergency", "MVEAGC 1: emergency-gc-capable", "MVIAG 1: no-imminent-peril" and
// "MVIAGC 1: imminent-peril-gc-capable" (steps 1 to 4).
void Client::mcvideo_adhoc_released(const std::string &group) {
  const auto found = mcvideo_adhoc_groups_.find(group);
  if (found == mcvideo_adhoc_groups_.end()) return;
  const bool priority = (*found)["emergency"] != "MVEAG 1: no-emergency" || (*found)["imminent"] != "MVIAG 1: no-imminent-peril";
  *found = {{"emergency", "MVEAG 1: no-emergency"},
            {"emergency_call", "MVEAGC 1: emergency-gc-capable"},
            {"imminent", "MVIAG 1: no-imminent-peril"},
            {"imminent_call", "MVIAGC 1: imminent-peril-gc-capable"}};
  if (priority) event("mcvideo_adhoc_priority_released", {{"group", group}});
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 6.2.4.3.2 step 2b and 6.2.5.3.3 step 1b with clause 9.2.3.11: the Transmission Indicator (B
// broadcast, D emergency, E imminent peril) of a broadcast, emergency or imminent peril call.
std::vector<FloorField> Client::mcvideo_indicator_fields() const {
  if (!mcvideo_call_) return {};
  std::uint16_t bits = 0;
  if (mcvideo_call_->broadcast) bits |= 0x4000;
  if (mcvideo_call_->priority == "emergency") bits |= 0x1000;
  if (mcvideo_call_->priority == "imminent") bits |= 0x0800;
  if (bits == 0) return {};
  return {floor_indicator_field(bits)};
}

//------------------------------------------------------------------------------
// TS 24.484 clause 9.3.2.1 item 8 e) v): the <Priority> of <MCVideo-group-call> in the MCVideo user profile (0 to
// 255), which TS 24.581 clauses 14.2.3 and 14.2.6 have the client base its transmission and reception priority on.
// TS 24.484 has no separate reception priority element; the profile's one priority of the user's MCVideo calls (TS
// 24.483 clause 13.2.38G6) is used for both. 0 is the default priority (TS 24.581 clauses 9.2.3.2 and 9.2.3.19).
unsigned Client::mcvideo_configured_priority() const {
  const auto found = mcvideo_profile_.find("priority");
  return found != mcvideo_profile_.end() && found->is_number_unsigned() ? found->get<unsigned>() : 0;
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 14.2.3 and 14.2.6: "mc_priority" / "mc_reception_priority" are offered "when a ... priority
// different than the default priority is required", with the configured value; the SDP syntax carries at most two
// digits (clause 12.1.2.3), so a higher configured value asks for 99.
std::optional<std::uint8_t> Client::mcvideo_offered_priority() const {
  return client_rules::offered_mc_priority(mcvideo_configured_priority());
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 6.2.4.3.2 step 2a and 6.2.4.4.3 step 1a: "if a different priority than the normal priority is
// required, shall include the Transmission Priority field with the priority not higher than negotiated with the
// transmission control server as specified in clause 14.3.3": the configured priority (above), capped by the
// negotiated "mc_priority". Not negotiated, the maximum is the default priority (clause 12.1.2.2 NOTE 1), and the
// field, which would carry the default priority, is left out (clause 9.2.3.2). Then the Transmission Indicator of
// step 2b.
std::vector<FloorField> Client::mcvideo_request_fields() const {
  std::vector<FloorField> fields;
  if (!mcvideo_call_) return fields;
  if (const auto priority = client_rules::request_priority(mcvideo_configured_priority(), mcvideo_call_->negotiated_priority))
    fields.push_back({static_cast<std::uint8_t>(transmission::Field::Priority), {*priority, 0}});
  for (auto &field : mcvideo_indicator_fields()) fields.push_back(std::move(field));
  return fields;
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 6.2.5.3.3 step 1a and 6.2.5.4.3 step 1a: the Reception Priority field (clause 9.2.3.19) "with the
// priority not higher than negotiated" ("mc_reception_priority", clauses 6.3.7.2 and 14.3.6), as for the
// Transmission Request; then the Transmission Indicator of step 1b.
std::vector<FloorField> Client::mcvideo_reception_fields() const {
  std::vector<FloorField> fields;
  if (!mcvideo_call_) return fields;
  if (const auto priority = client_rules::request_priority(mcvideo_configured_priority(), mcvideo_call_->negotiated_reception_priority))
    fields.push_back({static_cast<std::uint8_t>(transmission::Field::ReceptionPriority), {*priority, 0}});
  for (auto &field : mcvideo_indicator_fields()) fields.push_back(std::move(field));
  return fields;
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 9.2.1.2.1.3 to 9.2.1.2.1.5 (group call) and 10.2.2.2.4, 10.2.2.2.5 (private call): upgrade the
// call to emergency or imminent peril, or cancel it, by re-INVITE in the call's dialog, with the mcvideo-info body of
// TS 24.281 clauses 6.2.8.1 and 6.2.8.3.
//
// TS 24.281 clauses 6.2.8.1.14 (group) and 6.2.8.3.8 (private call): a cancellation may also cancel the emergency
// alert of another user (cancel_alert_of, its originator): <alert-ind> "false" and <originated-by> set to that user,
// for a user authorised to cancel emergency alerts (clauses 6.2.8.1.6 and 6.2.8.3.1.3). The cancelled emergency
// state is "cancel-pending" (MVEG 3, MVEPP 3) until the final response.
void Client::mcvideo_change_priority(bool emergency, bool enabled, const std::string &cancel_alert_of,
                                     bool own_alert) {
  // TS 24.281 annex G.1, state-clearing event "MCVideo user manually clears the state": outside a call, cancelling
  // the emergency clears the MCVideo emergency state locally (no signalling; an outstanding emergency alert is
  // cancelled with mcvideo-alert off, clause 11.2.1.2).
  if (emergency && !enabled && cancel_alert_of.empty() && !own_alert && !mcvideo_call_ && mcvideo_emergency_state_) {
    mcvideo_set_emergency_state(false, "cleared by the user");
    mcvideo_alert_result_ = {{"action", "emergency"}, {"enabled", false}, {"status", 0}, {"manual", true}};
    return;
  }
  if (!mcvideo_call_ || mcvideo_call_->state != "active") throw std::runtime_error("no active MCVideo call");
  auto &call = *mcvideo_call_;
  // The user's authorisation for the change (TS 24.281 clauses 6.2.8.1.7, 6.2.8.1.8, 6.2.8.1.10 for a group call,
  // clause 6.2.8.3.1 for a private call).
  if (call.type == "private") {
    if (!emergency) throw std::runtime_error("a private call has no imminent peril state");
    require_mcvideo_profile(enabled ? "allow-emergency-private-call" : "allow-cancel-private-emergency-call",
                            enabled ? "emergency private call" : "emergency private call cancellation");
    if (enabled) require_mcvideo_target("emergency_private_recipient", call.peer, "emergency private call");
  } else if (emergency) {
    require_mcvideo_profile(enabled ? "allow-emergency-group-call" : "allow-cancel-group-emergency",
                            enabled ? "emergency group call" : "in-progress emergency group state cancellation");
    if (enabled) require_mcvideo_target("emergency_group_initiation", call.group, "emergency group call");
  } else {
    require_mcvideo_profile(enabled ? "allow-imminent-peril-call" : "allow-cancel-imminent-peril",
                            enabled ? "imminent peril group call" : "imminent peril group call cancellation");
    if (enabled) require_mcvideo_target("imminent_group_initiation", call.group, "imminent peril group call");
  }
  McpttInfo info;
  info.session_type = call.type;
  info.client_id = client_id();
  if (call.type != "private") info.request_uri = call.group;

  if (!cancel_alert_of.empty()) {
    if (!emergency || enabled) throw std::runtime_error("only an emergency cancellation can cancel another user's alert");
    if (cancel_alert_of == mcvideo_user()) throw std::runtime_error("alert= names another user's emergency alert");
    require_mcvideo_profile("allow-cancel-emergency-alert", "emergency alert cancellation");
  }
  if (own_alert && (call.type != "private" || !emergency || !cancel_alert_of.empty()))
    throw std::runtime_error("the user's own emergency alert goes with an emergency private call change");
  // TS 24.281 clause 6.2.8.1.1 step 3: an emergency comes with an alert indication (false: no alert).
  bool alert_requested = false, alert_cancelled = false;
  if (emergency) {
    info.emergency = enabled;
    if (enabled) {
      // TS 24.281 clause 10.2.2.2.5 step 1 with clause 6.2.8.3.2 steps 3) and 4): a private call upgraded with the
      // user's emergency alert carries <alert-ind> "true" when the alert is authorised (clause 6.2.8.3.1.3).
      alert_requested = own_alert && mcvideo_private_alert_authorised(call.peer);
      info.alert = alert_requested;
    } else if (call.type == "private" && cancel_alert_of.empty() && call.alert_state != 1 && own_alert) {
      // TS 24.281 clause 6.2.8.3.6, third case: the emergency private call and the user's alert are cancelled: step 2)
      // <alert-ind> "false" and "MVPEA 4: emergency-alert-cancel-pending" when authorised (clause 6.2.8.3.1.3),
      // step 3) otherwise the user is told. With "MVPEA 1: no-alert" (first case) or the emergency private call alone
      // (second case) the body has <emergency-ind> "false" only.
      if (mcvideo_profile_allows("allow-cancel-emergency-alert")) {
        info.alert = false;
        alert_cancelled = true;
      } else {
        event("mcvideo_not_authorised",
              {{"action", "allow-cancel-emergency-alert"}, {"request", "emergency alert cancellation"}});
      }
    }
    if (!cancel_alert_of.empty()) {
      info.alert = false;                   // clauses 6.2.8.1.14 3) a), 6.2.8.3.8 3) a)
      info.originated_by = cancel_alert_of; // clauses 6.2.8.1.14 3) b), 6.2.8.3.8 3) b)
    }
  } else {
    info.imminent_peril = enabled;
  }
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  if (call.type == "private") parts.push_back({resource_lists_mime, private_call_resource_list(call.peer)});
  // TS 24.281 clause 6.2.8.3.2 step 3 b): the location report of the emergency alert trigger (clause 6.2.9.1).
  if (alert_requested)
    parts.push_back(
        {mcvideo_service.location_mime, service_location_xml(configured_emergency_location(), mcvideo_service)});

  // The re-INVITE asks for MCVideo like the initial INVITE (Accept-Contact, P-Preferred-Service).
  auto headers = mcvideo_call_headers();
  for (const auto &header : mcvideo_priority_headers(info)) headers.push_back(header);
  sip_.reinvite(
      call.dialog, call.local, parts,
      [this, emergency, enabled, cancel_alert_of, alert_requested](const SipResponse &response) {
        if (response.status_code < 200) return;
        if (mcvideo_call_ && mcvideo_call_->type == "private" && emergency)
          mcvideo_private_alert_answer(response, enabled, alert_requested, !cancel_alert_of.empty());
        mcvideo_alert_result_ = {{"action", emergency ? "emergency" : "imminent"},
                                 {"enabled", enabled},
                                 {"status", response.status_code},
                                 {"warning", std::string(get_header(response.headers, "Warning"))}};
        if (!cancel_alert_of.empty()) mcvideo_alert_result_["cancelled_alert_of"] = cancel_alert_of;
        if (response.status_code < 300 && mcvideo_call_)
          mcvideo_call_->priority = enabled ? (emergency ? "emergency" : "imminent") : "normal";
        // TS 24.281 annex G.1: an upgrade to an emergency call sets the MCVideo emergency state; the emergency call
        // cancelled by its initiator clears it when no emergency alert of the user is outstanding.
        if (response.status_code < 300 && emergency && enabled) mcvideo_set_emergency_state(true, "emergency call");
        if (response.status_code < 300 && emergency && !enabled && cancel_alert_of.empty() &&
            mcvideo_group_alert_state_ == 1 && !mcvideo_alerting_)
          mcvideo_set_emergency_state(false, "emergency call cancelled");
        if (response.status_code < 300 && !cancel_alert_of.empty()) mcvideo_alerts_.erase(cancel_alert_of);
        status_["mcvideo_priority_state"] = response.status_code < 300 ? (enabled ? "in-progress" : "none")
                                                                      : (enabled ? "none" : "in-progress");
      },
      headers);
  if (alert_requested) mcvideo_set_private_alert_state(2);
  if (alert_cancelled) {
    call.alert_state_before = call.alert_state;
    mcvideo_set_private_alert_state(4);
  }
  // MVEG 3 / MVEPP 3 (cancel-pending) or the pending upgrade, until the final response.
  status_["mcvideo_priority_state"] = enabled ? "requested" : "cancel-pending";
  event("mcvideo_priority_change_requested",
        {{"emergency", emergency}, {"enabled", enabled}, {"cancel_alert_of", cancel_alert_of}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.8.3.1.3: an authorised request for an MCVideo emergency alert to a user: 1)
// <allow-activate-emergency-alert> "true", and 2) the <entry> of <PrivateEmergencyAlert> of <OnNetwork> (TS 24.484
// clause 9.3.2.1 item 9 f)) a) "UsePreConfigured" with <uri-entry> the targeted user, or b) "LocallyDetermined"; "In
// all other cases" (also without the entry) unauthorised. An unauthorised alert does not stop the emergency private
// call (clause 6.2.8.3.2 step 3 applies to an authorised request only); the user is told (mcvideo_not_authorised).
bool Client::mcvideo_private_alert_authorised(const std::string &target) {
  const auto targets = mcvideo_profile_.value("targets", Json::object());
  const auto entry = targets.value("private_emergency_alert", Json::object());
  const auto info = entry.value("entry_info", std::string{});
  const bool allowed = mcvideo_profile_allows("allow-activate-emergency-alert") &&
                       (info == "LocallyDetermined" || (info == "UsePreConfigured" && entry.value("uri", std::string{}) == target));
  if (!allowed)
    event("mcvideo_not_authorised", {{"action", "allow-activate-emergency-alert"},
                                     {"request", "emergency alert to a user"},
                                     {"target", {{"entry", "PrivateEmergencyAlert"}, {"entry_info", info}, {"target", target}}}});
  return allowed;
}

//------------------------------------------------------------------------------
// Helper: the MCVideo private emergency alert state of the private call (TS 24.281 clauses 6.2.8.3.2 to 6.2.8.3.7).
void Client::mcvideo_set_private_alert_state(int state) {
  static const char *names[] = {"", "MVPEA 1: no-alert", "MVPEA 2: emergency-alert-confirm-pending",
                                "MVPEA 3: emergency-alert-initiated", "MVPEA 4: Emergency-alert-cancel-pending"};
  if (!mcvideo_call_ || state < 1 || state > 4) return;
  const auto before = mcvideo_call_->alert_state;
  mcvideo_call_->alert_state = state;
  status_["mcvideo_private_alert_state"] = names[state];
  if (before != state) event("mcvideo_private_alert_state", {{"state", names[state]}, {"from", names[before]}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.5 (upgrade: 2xx -> clause 6.2.8.3.4 step 3, 4xx to 6xx -> clause 6.2.8.3.5 step 3) and
// clause 10.2.2.2.4 (cancellation: 2xx step 4, 4xx to 6xx steps 2 and 3).
void Client::mcvideo_private_alert_answer(const SipResponse &response, bool upgrade, bool alert_requested,
                                          bool third_party) {
  auto &call = *mcvideo_call_;
  if (upgrade) {
    if (response.status_code < 300 && call.alert_state == 2 && !info_pending(response))
      mcvideo_set_private_alert_state(3);
    else if (response.status_code >= 300 && alert_requested)
      mcvideo_set_private_alert_state(1);
    return;
  }
  if (response.status_code < 300) {
    if (call.alert_state == 4 && !third_party && !info_pending(response)) mcvideo_set_private_alert_state(1);
    return;
  }
  std::optional<McpttInfo> info;
  for (const auto &part :
       response.parts.empty() ? parse_body_parts(response.content_type, response.body) : response.parts)
    if (part.content_type.starts_with(mcvideo_service.info_mime)) info = parse_service_info(part.body, mcvideo_service);
  if (info && info->alert == true && !third_party)
    mcvideo_set_private_alert_state(3); // step 2
  else if (!info && call.alert_state == 4)
    mcvideo_set_private_alert_state(call.alert_state_before); // step 3
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.8.3.7 (from clauses 10.2.2.2.1, 10.2.2.2.4 and 10.2.2.2.5): the controlling function confirms
// or corrects the emergency alert request of a priority private call in a SIP INFO request (clause 6.3.3.1.17) with
// one or more of <alert-ind>, <imminentperil-ind> and <emergency-ind>: 1) from "MVPEA 2: emergency-alert-confirm-
// pending", a) "false" -> "MVPEA 1: no-alert", b) "true" -> "MVPEA 3: emergency-alert-initiated"; 2) from "MVPEA 4:
// Emergency-alert-cancel-pending", a) "true" -> "MVPEA 3", b) "false" -> "MVPEA 1". The user is told the outcome.
void Client::mcvideo_private_alert_info(const McpttInfo &info) {
  auto &call = *mcvideo_call_;
  const auto before = call.alert_state;
  if (info.alert && (before == 2 || before == 4)) mcvideo_set_private_alert_state(*info.alert ? 3 : 1);
  event("mcvideo_private_priority_info",
        {{"emergency", info.emergency ? Json(*info.emergency) : Json()},
         {"alert", info.alert ? Json(*info.alert) : Json()},
         {"alert_state", status_.value("mcvideo_private_alert_state", std::string("MVPEA 1: no-alert"))},
         {"alert_refused", before == 2 && info.alert == false},
         {"alert_cancel_refused", before == 4 && info.alert == true}});
}

//------------------------------------------------------------------------------
// Helper: the MCVideo emergency alert state (TS 24.281 annex G.5) of the alert sent with an emergency group call, in
// the status document.
void Client::mcvideo_set_group_alert_state(int state) {
  static const char *names[] = {"", "MVEA 1: no-alert", "MVEA 2: emergency-alert-confirm-pending",
                                "MVEA 3: emergency-alert-initiated"};
  if (state < 1 || state > 3) return;
  mcvideo_group_alert_state_ = state;
  status_["mcvideo_alert_state"] = names[state];
}

//------------------------------------------------------------------------------
// Helper: the MCVideo emergency state (TS 24.281 annex G.1), in the status document.
void Client::mcvideo_set_emergency_state(bool set, const char *cause) {
  if (mcvideo_emergency_state_ == set) return;
  mcvideo_emergency_state_ = set;
  status_["mcvideo_emergency_state"] = set ? "set" : "clear";
  event("mcvideo_emergency_state", {{"state", set ? "set" : "clear"}, {"cause", cause}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 11.2.1.1: an MCVideo emergency alert on the configured group, with this UE's location, or its
// cancellation (TS 24.281 clause 11.2.1.2).
void Client::mcvideo_alert(bool enabled) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo alert requires authorisation");
  // TS 24.281 clause 6.2.8.1.6: the user's authorisation for the alert or its cancellation.
  require_mcvideo_profile(enabled ? "allow-activate-emergency-alert" : "allow-cancel-emergency-alert",
                          enabled ? "emergency alert" : "emergency alert cancellation");
  // Clause 6.2.8.1.6 2): the alert's target, the configured group.
  if (enabled) require_mcvideo_target("group_emergency_alert", mcvideo_group(), "emergency alert");
  McpttInfo info;
  info.request_uri = mcvideo_group();
  info.client_id = client_id();
  info.alert = enabled; // alert-ind alone: the alert is not an emergency call (TS 24.281 clause 11.2.1.1)
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  if (enabled)
    parts.push_back(
        {mcvideo_service.location_mime, service_location_xml(configured_emergency_location(), mcvideo_service)});
  const auto boundary = "mcvideo-" + random_id();
  const auto headers = mcvideo_call_headers();
  const auto [type, body] = message_body(parts, boundary);
  sip_.request("MESSAGE", mcvideo_service_uri(), type, body, headers, [this, enabled](const SipResponse &response) {
    if (response.status_code < 200) return;
    mcvideo_alert_result_ = {{"action", "alert"},
                             {"enabled", enabled},
                             {"status", response.status_code},
                             {"warning", std::string(get_header(response.headers, "Warning"))}};
    if (response.status_code < 300) mcvideo_alerting_ = enabled;
    // TS 24.281 annex G.1: the emergency alert initiated sets the MCVideo emergency state, its cancellation by the
    // initiator clears it.
    if (response.status_code < 300) mcvideo_set_emergency_state(enabled, enabled ? "emergency alert" : "emergency alert cancelled");
  });
  event("mcvideo_alert_requested", {{"enabled", enabled}});
}

//------------------------------------------------------------------------------
// Helper: the MCVideo imminent peril group state of a group (TS 24.281 annex G.7), in the status document.
void Client::mcvideo_set_imminent_state(const std::string &group, int state) {
  if (group.empty()) return;
  static const char *names[] = {"", "MVIG 1: no-imminent-peril", "MVIG 2: in-progress", "MVIG 3: cancel-pending"};
  mcvideo_imminent_groups_[group] = names[state];
  status_["mcvideo_imminent_groups"] = mcvideo_imminent_groups_;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.5.2.1: a SIP MESSAGE (out of dialog) to the participating MCVideo function cancelling the
// in-progress imminent peril state of a prearranged group on which there is no call: P-Preferred-Service and
// Accept-Contact with the MCVideo ICSI (steps 2, 3), the mcvideo-info body with the group, <imminentperil-ind> "false",
// the client ID and the functional alias in use (step 5), the state "MVIG 3: cancel-pending" (step 7); a 2xx sets
// "MVIG 1: no-imminent-peril", a 4xx, 5xx or 6xx "MVIG 2: in-progress".
void Client::mcvideo_imminent_cancel(const std::string &target) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo requires authorisation");
  const auto group = target.empty() ? mcvideo_group() : target;
  // With this client in a call on the group, the cancellation is the call's re-INVITE (clause 9.2.1.2.1.5).
  if (mcvideo_call_ && mcvideo_call_->group == group && mcvideo_call_->state == "active")
    return mcvideo_change_priority(false, false);
  // Step 1: the user's authorisation (clause 6.2.8.1.10); unauthorised, the user is told and nothing is sent.
  require_mcvideo_profile("allow-cancel-imminent-peril", "in-progress imminent peril group state cancellation");
  McpttInfo info;
  info.request_uri = group;                           // step 5 a)
  info.imminent_peril = false;                        // step 5 b)
  info.client_id = client_id();                       // step 5 c)
  info.functional_alias = mcvideo_presented_alias();  // step 5 d)
  const std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  const auto [type, body] = message_body(parts, "mcvideo-" + random_id());
  mcvideo_set_imminent_state(group, 3); // step 7
  sip_.request("MESSAGE", mcvideo_service_uri(), type, body, mcvideo_call_headers(), // steps 2, 3, 6, 8
               [this, group](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_set_imminent_state(group, response.status_code < 300 ? 1 : 2);
                 mcvideo_alert_result_ = {{"action", "imminent-state-cancel"},
                                          {"group", group},
                                          {"status", response.status_code},
                                          {"warning", std::string(get_header(response.headers, "Warning"))}};
               });
  event("mcvideo_imminent_cancel_requested", {{"group", group}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.0.1: the SIP MESSAGE requests to the MCVideo client, by kind: ad hoc group
// emergency alert, group selection change, and
// (TS 24.281 clause 11.2.1.3) an alert of another user from the controlling function, or its cancellation.
SipResponse Client::mcvideo_message(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  const auto info = message_info(message, mcvideo_service);
  // TS 24.281 clause 8.2.1.5: an affiliation status change request in negotiated mode.
  const bool command = message.content_type == "application/vnd.3gpp.mcvideo-affiliation-command+xml" ||
                       std::any_of(message.parts.begin(), message.parts.end(), [](const auto &part) {
                         return part.content_type == "application/vnd.3gpp.mcvideo-affiliation-command+xml";
                       });
  if (command) return affiliation_command_message(mcvideo_service, message);

  // TS 24.281 clause 12.1A.1.5: the controlling function's response to this user's participant criteria
  // modification (<resp-type> "ad-hoc-emergency-alert-participant-criteria-modification"): the ad hoc group identity
  // and the criteria are stored (step 1) and shown (step 2); the 200 (OK) answers it (step 3).
  if (const auto resp = info.service.find("resp-type");
      resp != info.service.end() && resp->second == "ad-hoc-emergency-alert-participant-criteria-modification") {
    if (!info.calling_group.empty()) mcvideo_adhoc_alert_group_ = info.calling_group;
    mcvideo_adhoc_alert_criteria_ = info.participant_criteria;
    status_["mcvideo_adhoc_alert_criteria"] = {{"group", mcvideo_adhoc_alert_group_},
                                               {"criteria", mcvideo_adhoc_alert_criteria_}};
    event("mcvideo_adhoc_alert_criteria_modified",
          {{"group", mcvideo_adhoc_alert_group_}, {"criteria", mcvideo_adhoc_alert_criteria_}});
    return response;
  }
  // TS 24.281 clause 22.2.7.1: a "SIP MESSAGE request with notification of ad hoc group call redirection" (clause
  // 6.2.0.1: <anyExt> with <forwarding-reason> "migrated", from clause 6.3.3.1.25: the ad hoc group in
  // <mcvideo-calling-group-id>, the migrating user's <partner-mcvideo-id> and <primary-mcvideo-id>): the user is
  // notified of the redirection (step 1) and the MESSAGE answered 200 (OK) (step 2).
  if (const auto reason = info.service.find("forwarding-reason");
      reason != info.service.end() && reason->second == "migrated") {
    Json entry{{"group", info.calling_group},
               {"partner_id", info.partner_mcvideo_id},
               {"primary_id", info.primary_mcvideo_id},
               {"in_call", mcvideo_call_ && mcvideo_call_->type == "adhoc" && mcvideo_call_->group == info.calling_group}};
    mcvideo_adhoc_redirections_.push_back(entry);
    status_["mcvideo_adhoc_redirections"] = mcvideo_adhoc_redirections_;
    event("mcvideo_adhoc_call_redirected", entry);
    return response;
  }
  // TS 24.281 clause 12.1A.1.3: an ad hoc group emergency alert notification, its cancellation, or the
  // receipt of this user's own alert.
  if (info.adhoc_alert.has_value()) {
    if (info.request_uri != mcvideo_user() || info.calling_group.empty()) {
      response.status_code = 403;
      return response;
    }
    if (info.adhoc_alert_received == true) {
      if (info.client_id != client_id()) {
        response.status_code = 403;
        return response;
      }
      // TS 24.281 clause 12.1A.1.3: the receipt of this user's alert, or of its cancellation, which ends it.
      if (*info.adhoc_alert) {
        if (mcvideo_ah_mea_ == 2) mcvideo_ah_mea_ = 3;
        mcvideo_adhoc_alert_group_ = info.calling_group;
      } else if (mcvideo_ah_mea_ == 4) {
        mcvideo_ah_mea_ = 1;
        mcvideo_adhoc_alert_group_.clear();
      }
    } else if (*info.adhoc_alert) {
      mcvideo_adhoc_alerts_[info.calling_group] = {{"initiator", info.calling_user},
                                                   {"criteria", info.participant_criteria}};
    } else {
      mcvideo_adhoc_alerts_.erase(info.calling_group);
    }
    event("mcvideo_adhoc_alert_notification", {{"group", info.calling_group}, {"active", *info.adhoc_alert}});
    return response;
  }
  // TS 24.281 clauses 9.2.4.2.2 and 9.2.4.2.1: a group selection change request, or the response to ours.
  if (info.service.contains("request-type") && info.service.at("request-type") == "group-selection-change-request") {
    schedule([this, info] { selection_requested(mcvideo_service, info); });
    return response;
  }
  if (info.service.contains("response-type") && info.service.at("response-type") == "group-selection-change-response") {
    selection_outcome(mcvideo_service, info);
    return response;
  }
  if (info.alert.has_value()) {
    const auto origin = info.originated_by.empty() ? info.calling_user : info.originated_by;
    if (*info.alert) {
      Json entry{{"group", info.calling_group.empty() ? info.request_uri : info.calling_group}};
      const auto parts = message.parts.empty() ? parse_body_parts(message.content_type, message.body) : message.parts;
      if (const auto location = parse_emergency_location_parts(parts))
        entry["location"] = {{"latitude", location->latitude}, {"longitude", location->longitude}};
      mcvideo_alerts_[origin] = entry;
    } else {
      mcvideo_alerts_.erase(origin);
    }
    event("mcvideo_alert_received", {{"from", origin}, {"active", *info.alert}});
  }
  // TS 24.281 clause 9.2.5.2.2: a notification of the cancellation of a group's in-progress imminent peril state
  // (<imminentperil-ind> "false"): the user is shown the group and the user who cancelled it (step 1 a)), the group's
  // state is "MVIG 1: no-imminent-peril" (step 1 b)); the 200 (OK) answers it (steps 2, 3).
  if (info.imminent_peril == false && !info.alert.has_value()) {
    const auto group = info.calling_group.empty() ? info.request_uri : info.calling_group;
    mcvideo_set_imminent_state(group, 1);
    event("mcvideo_imminent_state_cancelled", {{"group", group}, {"by", info.calling_user}});
  }
  mcvideo_note_priority(info);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.1: the final response to this client's INVITE: a failure ends the call, or is added to
// the record of a call that the dialog's end has already closed.
void Client::mcvideo_invite_answered(unsigned generation, const SipResponse &response) {
  if (response.status_code < 200) return;
  // TS 24.281 clauses 6.2.8.1.4 step 1 b) and 6.2.8.1.5 step 1 c): the final response to an emergency group call that
  // carried this user's emergency alert: a 2xx without the mcvideo-warn-code "149" (a SIP INFO request follows,
  // clause 6.2.8.1.13) initiates the alert ("MVEA 3: emergency-alert-initiated"); a 4xx, 5xx or 6xx returns it to
  // "MVEA 1: no-alert". Kept by the call's generation: a refused INVITE's dialog may have ended the call already.
  if (mcvideo_group_alert_generation_ == generation && generation != 0 && response.status_code != 407 &&
      response.status_code != 401) {
    if (response.status_code < 300 && mcvideo_group_alert_state_ == 2 && !info_pending(response)) {
      mcvideo_set_group_alert_state(3);
      mcvideo_alerting_ = true;
    } else if (response.status_code >= 300 && mcvideo_group_alert_state_ == 2) {
      mcvideo_set_group_alert_state(1);
    }
    mcvideo_group_alert_generation_ = 0;
  }
  // TS 33.180 clause 5.6 ("The receiving MCX client and any MCX Server through which the SIP INVITE is routed, may
  // respond with a KMS Redirect Response (KRR)") and annex D.4.1 (a SIP 488 with the KRR body).
  if (response.status_code == 488 && mcvideo_kms_redirect(generation, response)) return;
  // TS 24.281 clause 10.2.2.2.1: a 300 to a call to a functional alias names, in <mcvideo-request-uri>,
  // the MCVideo user to call instead with <called-functional-alias-URI>.
  if (mcvideo_call_ && mcvideo_call_->generation == generation && mcvideo_call_->to_alias)
    mcvideo_alias_call_ = {
        {"generation", generation}, {"alias", mcvideo_call_->called_alias}, {"mode", mcvideo_call_->mode}};
  if (response.status_code == 300 && mcvideo_alias_call_.value("generation", 0u) == generation) {
    std::string selected;
    for (const auto &part :
         response.parts.empty() ? std::vector<BodyPart>{{response.content_type, response.body}} : response.parts)
      if (part.content_type.starts_with(mcvideo_service.info_mime))
        selected = parse_service_info(part.body, mcvideo_service).request_uri;
    const std::string alias = mcvideo_alias_call_.at("alias"), mode = mcvideo_alias_call_.at("mode");
    mcvideo_alias_call_ = Json::object();
    if (mcvideo_call_ && mcvideo_call_->generation == generation) mcvideo_call_.reset();
    mcvideo_redirect_ = {{"alias", alias}, {"selected", selected}};
    event("mcvideo_functional_alias_redirect", mcvideo_redirect_);
    if (selected.empty() || selected == mcvideo_user()) {
      failure("MCVideo 300 without a user to call");
      return;
    }
    schedule(
        [this, selected, mode, alias] { mcvideo_private_call(selected, mode, false, false, "private", false, alias); });
    return;
  }
  if (mcvideo_call_ && mcvideo_call_->generation == generation) {
    if (response.status_code < 300) {
      mcvideo_call_->session_uri = contact_uri_of(get_header(response.headers, "Contact"));
      // TS 24.281 clause 10.2.2.2.1 2xx step 2 and clause 6.2.8.3.4 step 3: the alert is initiated unless the 2xx
      // says, with the mcvideo-warn-code "149", that a SIP INFO request will follow (clause 6.2.8.3.7).
      if (mcvideo_call_->type == "private" && mcvideo_call_->alert_state == 2 && !info_pending(response))
        mcvideo_set_private_alert_state(3);
      if (!mcvideo_call_->ambient_type.empty())
        mcvideo_call_->may_release = ambient_release_capability(response.headers);
      // TS 24.281 clause 22.2.2.1.1, 2xx step 4: the ad hoc group identity the server assigned.
      if (mcvideo_call_->type == "adhoc")
        for (const auto &part : response.parts)
          if (part.content_type == mcvideo_service.info_mime)
            if (const auto group = parse_service_info(part.body, mcvideo_service).calling_group; !group.empty())
              mcvideo_call_->group = group;
      // TS 24.281 clause 6.2.8.1.20: the priority ad hoc call is granted ("MVEAGC 3: emergency-call-granted", the ad
      // hoc group "MVEAG 2: in-progress"; or the imminent peril equivalents).
      if (const auto priority = std::exchange(mcvideo_call_->priority_requested, {}); !priority.empty()) {
        mcvideo_call_->priority = priority;
        status_["mcvideo_adhoc_priority_state"] = priority == "emergency" ? "MVEAGC 3: emergency-call-granted"
                                                                          : "MVIAGC 3: imminent-peril-call-granted";
      }
      return;
    }
    // TS 24.281 clause 6.2.8.3.5 step 3: a refused emergency private call with the alert ends the alert request.
    if (mcvideo_call_->type == "private" && mcvideo_call_->alert_state == 2) mcvideo_set_private_alert_state(1);
    // TS 24.281 clause 6.2.8.1.21: a refused priority ad hoc call goes back to "MVEAGC 1: emergency-gc-capable"
    // ("MVIAGC 1: imminent-peril-gc-capable"), the ad hoc group to no emergency (no imminent peril).
    if (!mcvideo_call_->priority_requested.empty())
      status_["mcvideo_adhoc_priority_state"] = mcvideo_call_->priority_requested == "emergency"
                                                    ? "MVEAGC 1: emergency-gc-capable"
                                                    : "MVIAGC 1: imminent-peril-gc-capable";
    mcvideo_end_call("refused");
  }
  if (response.status_code >= 300 && mcvideo_last_call_.value("generation", 0u) == generation) {
    mcvideo_last_call_["status"] = response.status_code;
    mcvideo_last_call_["warning"] = std::string(get_header(response.headers, "Warning"));
  }
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.2.8.2.5, KMS selection at the initiator on receipt of a KRR ("Client shall support receipt of
// KRRs", clause 5.2.8.1.1), for the private call request whose INVITE it answers:
//   1. the initiator KMS (to sign): with "ANY" the Home KMS and the provisioned Migration KMSs, else the listed KMS
//      URIs that are this client's Home KMS or a Migration KMS (none are provisioned here: the Home KMS signs, the only
//      identity keys held); the Home KMS if listed;
//   2. the receiver KMS (to encrypt): with "ANY" the Home, Migration and External KMSs, else the listed ones this
//      client holds a certificate for (the clause's "reduced list"); "If the KMS list contains the initiator's Home
//      KMS URI; the initiator shall use the Home KMS ... else ... select the first KMS URI from the list";
//   "If the reduced list contains no KMS URIs, then the communication fails"; otherwise "the initiator shall send a
//   new MIKEY message using the selected KMSs": the call request is placed again. A KRR for another MIKEY message
//   (its IDRi, IDRr), one that selects the KMSs already used, or more than three for one request ends the request.
bool Client::mcvideo_kms_redirect(unsigned generation, const SipResponse &response) {
  // The failed INVITE's dialog may have ended the call already (SipCallState::disconnected comes first): its figures
  // are then in mcvideo_last_call_.
  const bool live = mcvideo_call_ && mcvideo_call_->generation == generation;
  if (!live && mcvideo_last_call_.value("generation", 0u) != generation) return false;
  const std::string type = live ? mcvideo_call_->type : mcvideo_last_call_.value("type", std::string{});
  const std::string target = live ? mcvideo_call_->peer : mcvideo_last_call_.value("peer", std::string{});
  if (type != "private" || target.empty() || !mcvideo_keys_ || !mcvideo_private_retry_) return false;
  const auto body = krr_body(response);
  if (!body) return false;
  Json record{{"target", target}};
  std::string chosen;
  try {
    const auto krr = keying::parse_krr(*body);
    record.update({{"creator", krr.creator},
                   {"initiator_kms", krr.initiator_kms},
                   {"receiver_kms", krr.receiver_kms},
                   {"initiator_list", krr.initiator_any ? Json("ANY") : Json(krr.initiator_list)},
                   {"receiver_list", krr.receiver_any ? Json("ANY") : Json(krr.receiver_list)}});
    if (krr.initiator != mcvideo_user() || krr.receiver != target)
      throw std::runtime_error("a KMS Redirect Response for another MIKEY message");
    if (mcvideo_krr_count_ >= 3) throw std::runtime_error("too many KMS Redirect Responses");
    const auto known = mcvideo_keys_->known_kms();
    if (known.empty()) throw std::runtime_error("KMS identity not provisioned");
    const auto &home = known.front();
    const auto listed = [](const std::vector<std::string> &list, const std::string &uri) {
      return std::find(list.begin(), list.end(), uri) != list.end();
    };
    if (!krr.initiator_any && !listed(krr.initiator_list, home))
      throw std::runtime_error("no acceptable initiator KMS");
    std::vector<std::string> receivers;
    if (krr.receiver_any)
      receivers = known;
    else
      for (const auto &uri : krr.receiver_list)
        if (listed(known, uri)) receivers.push_back(uri);
    if (receivers.empty()) throw std::runtime_error("no acceptable receiver KMS");
    chosen = listed(receivers, home) ? home : receivers.front();
    record["selected_initiator_kms"] = home;
    record["selected_receiver_kms"] = chosen;
    if (chosen == krr.receiver_kms && home == krr.initiator_kms)
      throw std::runtime_error("the KMS Redirect Response selects the KMSs already used");
    record["result"] = "new MIKEY message";
  } catch (const std::exception &error) {
    record["result"] = std::string("communication fails: ") + error.what();
    mcvideo_last_krr_ = record;
    status_["mcvideo_last_krr"] = record;
    event("mcvideo_kms_redirect", record);
    return false;
  }
  ++mcvideo_krr_count_;
  mcvideo_last_krr_ = record;
  status_["mcvideo_last_krr"] = record;
  event("mcvideo_kms_redirect", record);
  if (live) {
    if (mcvideo_call_->alert_state == 2) mcvideo_set_private_alert_state(1);
    mcvideo_end_call("kms-redirect");
  }
  mcvideo_redirect_kms_ = {target, chosen};
  schedule([this, retry = mcvideo_private_retry_] {
    try {
      retry();
    } catch (const std::exception &error) {
      failure(std::string("MCVideo private call after a KMS Redirect Response: ") + error.what());
    }
  });
  return true;
}

//------------------------------------------------------------------------------
// Helper: a new MCVideo call with its own ports (mcvideo.media): the MCPTT call keeps media.rtp_port and floor_port.
Client::McvideoCall Client::new_mcvideo_call() {
  McvideoCall call;
  call.generation = ++mcvideo_generation_;
  const auto &ports = cfg_.at("mcvideo").at("media");
  auto &local = call.local;
  local.control = MediaControl::transmission;
  local.address = local.floor_address = resolve_ipv4(cfg_.at("media").at("advertise"));
  local.audio_port = static_cast<std::uint16_t>(ports.at("audio_port").get<unsigned>());
  local.rtcp_port = static_cast<std::uint16_t>(local.audio_port + 1);
  local.floor_port = static_cast<std::uint16_t>(ports.at("control_port").get<unsigned>());
  local.floor_ssrc = random_ssrc();
  local.audio_ssrc = random_ssrc();
  // mc_queueing (TS 24.581 clause 14.2.2): this client queues its Transmission Requests
  // unless mcvideo.queueing is false.
  local.queueing = cfg_.at("mcvideo").value("queueing", true);
  // TS 24.581 clauses 14.2.3 and 14.2.6 (and 14.5 for later offers): a configured priority other than the default
  // is offered as "mc_priority" and "mc_reception_priority".
  local.floor_priority = local.reception_priority = mcvideo_offered_priority();
  VideoMedia video;
  video.port = static_cast<std::uint16_t>(ports.at("video_port").get<unsigned>());
  video.rtcp_port = static_cast<std::uint16_t>(video.port + 1);
  video.ssrc = random_ssrc();
  local.video = video;
  const auto bind = cfg_.at("media").at("bind").get<std::string>();
  call.audio = std::make_unique<UdpSocket>(bind, local.audio_port);
  call.video = std::make_unique<UdpSocket>(bind, video.port);
  call.control = std::make_unique<UdpSocket>(bind, local.floor_port);
  call.decoder = std::make_unique<video::Decoder>();
  return call;
}

//------------------------------------------------------------------------------
// TS 24.581 clause 14.2.4: the offer of the initial INVITE includes "mc_granted" "when it is acceptable for the
// MCVideo client to receive a granted indication in the SIP 200 (OK) response": with an implicit transmission request
// of this client's own (clause 14.3.4: the answer may grant that implicit request), not for the originator of a
// remotely initiated ambient viewing call, whose implicit request asks the viewed user to transmit (TS 24.281 clause
// 6.4); mcvideo.offer_granted false leaves it out. Clause 14.5: later offers (call.local) never carry it.
MediaDescription Client::mcvideo_initial_offer(McvideoCall &call) const {
  auto offer = call.local;
  call.offered_granted = offer.control == MediaControl::transmission && offer.implicit_request &&
                         call.ambient_type != "remote-init" && cfg_.at("mcvideo").value("offer_granted", true);
  offer.granted = call.offered_granted;
  return offer;
}

//------------------------------------------------------------------------------
// Helper: the URI inside the angle brackets of a Contact header field value.
std::string Client::contact_uri_of(std::string_view contact) {
  const auto open = contact.find('<'), close = contact.find('>');
  if (open == std::string_view::npos || close == std::string_view::npos || close < open) return {};
  return std::string(contact.substr(open + 1, close - open - 1));
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.1 steps 4 to 6: the MCVideo ICSI in P-Preferred-Service and the Accept-Contact header
// fields with g.3gpp.icsi-ref and g.3gpp.mcvideo, "require" and "explicit".
std::vector<SipHeader> Client::mcvideo_call_headers() const {
  return {
      {"P-Preferred-Service", mcvideo_service.icsi},
      {"Accept-Contact", std::string("*;+g.3gpp.icsi-ref=\"") + mcvideo_service.icsi_encoded + "\";require;explicit"},
      {"Accept-Contact", std::string("*;+") + mcvideo_service.feature_tag + ";require;explicit"}};
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.1: a private call with transmission control to one MCVideo ID, in a commencement mode
// (TS 24.281 clause 6.2.3, step 11): Answer-Mode Auto or Manual, or Priv-Answer-Mode Auto. With no_control,
// TS 24.281 clause 10.2.3.1: the offer has no transmission control, and both parties send. A one-to-one video push or pull
// (TS 24.281 clauses 13.2.2.1, 12.2.2.1) is this call with its <session-type>.
void Client::mcvideo_private_call(const std::string &target, std::string mode, bool no_control, bool emergency,
                                  const std::string &session_type, bool to_alias, const std::string &called_alias,
                                  bool implicit, bool alert) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo call requires authorisation");
  if (mcvideo_call_) throw std::runtime_error("an MCVideo call is in progress");
  if (target.empty() || target == mcvideo_user()) throw std::runtime_error("private call requires another MCVideo ID");
  // TS 24.281 clause 6.2.8.3.1.1: an emergency private call needs <allow-emergency-private-call>.
  if (emergency) require_mcvideo_profile("allow-emergency-private-call", "emergency private call");
  if (emergency) require_mcvideo_target("emergency_private_recipient", target, "emergency private call");
  // TS 24.281 clause 6.2.8.3.2 step 3: the emergency alert is requested with the emergency private call.
  if (alert && !emergency) throw std::runtime_error("an emergency alert in a private call needs an emergency call");
  if (mode.empty()) mode = "auto";
  // TS 33.180 clause 5.2.8.2.5: a new request of the user starts its KMS selection afresh; a request placed again
  // after a KMS Redirect Response keeps the count and the selected receiver KMS.
  if (!std::exchange(mcvideo_krr_retry_, false)) {
    mcvideo_krr_count_ = 0;
    mcvideo_redirect_kms_ = {};
  }
  mcvideo_private_retry_ = [this, target, mode, no_control, emergency, session_type, to_alias, called_alias, implicit,
                            alert] {
    mcvideo_krr_retry_ = true;
    mcvideo_private_call(target, mode, no_control, emergency, session_type, to_alias, called_alias, implicit, alert);
  };
  auto headers = mcvideo_call_headers();
  if (mode == "auto")
    headers.push_back({"Answer-Mode", "Auto"});
  else if (mode == "manual")
    headers.push_back({"Answer-Mode", "Manual"});
  else if (mode == "force-auto")
    headers.push_back({"Priv-Answer-Mode", "Auto"});
  else
    throw std::runtime_error("private call mode must be auto, manual or force-auto");
  mcvideo_call_ = new_mcvideo_call();
  const McvideoCallSetup setup{mcvideo_call_};
  auto &call = *mcvideo_call_;
  call.type = "private";
  call.peer = target;
  call.state = "calling";
  status_["mcvideo_private_alert_state"] = "MVPEA 1: no-alert";
  call.mode = mode;
  call.called_alias = to_alias ? target : called_alias;
  call.to_alias = to_alias;
  if (session_type != "private") {
    call.video_mode = session_type;
    // TS 24.281 clause 13.2.2.1 step 2 with TS 24.581 clause 6.2: the pushing caller asks to transmit in its offer.
    call.local.implicit_request = session_type == "one-to-one video push";
  }
  if (no_control) {
    call.local.control = MediaControl::none;
    call.local.queueing = false;
    call.local.floor_priority = call.local.reception_priority = std::nullopt; // no media plane control channel
  } else if (implicit) {
    // TS 24.281 clause 6.4 with TS 24.581 clause 14.2.5: the user asked to transmit with the call setup.
    call.local.implicit_request = true;
    call.requesting = true;
  }
  McpttInfo info;
  info.session_type = session_type;
  info.client_id = client_id();

  // TS 24.281 clause 10.2.2.2.1 step 11 c) ii): <call-to-functional-alias-ind> for a call to an alias, and
  // the alias this user presents. After a 300, <called-functional-alias-URI> names the
  // alias first called and the indication is left out (300 steps 2 and 3).
  if (to_alias) info.call_to_functional_alias = true;
  info.called_functional_alias = called_alias;
  info.functional_alias = mcvideo_presented_alias();

  // TS 24.281 clause 6.2.8.3.2: an emergency private call (step 2), with the user's emergency alert when it is an
  // authorised request (step 3 a): <alert-ind> "true", "MVPEA 2: emergency-alert-confirm-pending"; step 3 b): the
  // location report of clause 6.2.9.1), otherwise <alert-ind> "false" (step 4).
  bool with_alert = false;
  if (emergency) {
    info.emergency = true;
    mcvideo_set_emergency_state(true, "emergency private call"); // annex G.1: "MCVideo emergency private call initiated"
    with_alert = alert && mcvideo_private_alert_authorised(target);
    info.alert = with_alert;
    call.priority = "emergency";
    for (const auto &header : mcvideo_priority_headers(info)) headers.push_back(header);
  }
  // The PCK is for the user called; a call to a functional alias learns that user
  // only from the 300, so it is not protected.
  if (to_alias && protected_media())
    throw std::runtime_error("a protected call to a functional alias is not supported");
  const auto pck = mcvideo_offer_pck(target);
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                              {resource_lists_mime, private_call_resource_list(target)}};
  if (with_alert) {
    parts.push_back(
        {mcvideo_service.location_mime, service_location_xml(configured_emergency_location(), mcvideo_service)});
    mcvideo_set_private_alert_state(2);
  }
  call.dialog = sip_.invite(
      mcvideo_service_uri(), mcvideo_initial_offer(call), parts,
      [this, generation = call.generation](const SipResponse &response) {
        mcvideo_invite_answered(generation, response);
      },
      headers);
  if (pck) mcvideo_bind_pck(*pck, true);
  event("mcvideo_private_call_requested",
        {{"target", target}, {"mode", mode}, {"session_type", session_type}, {"to_alias", to_alias}});
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 13.2.2.4 (one-to-server video push) and 12.2.2.4 (one-from-server video pull): a call to the
// MCVideo server itself, without a resource-lists body and with automatic commencement. A push asks to transmit in
// its offer (TS 24.581 clause 6.2); a pull names the video in <video-pull-url>.
void Client::mcvideo_server_call(const std::string &session_type, const std::string &url) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo call requires authorisation");
  if (mcvideo_call_) throw std::runtime_error("an MCVideo call is in progress");
  auto headers = mcvideo_call_headers();
  headers.push_back({"Answer-Mode", "Auto"});
  mcvideo_call_ = new_mcvideo_call();
  const McvideoCallSetup setup{mcvideo_call_};
  auto &call = *mcvideo_call_;
  call.type = "private";
  call.video_mode = session_type;
  call.peer = mcvideo_service_uri();
  call.state = "calling";
  call.local.implicit_request = session_type == "one-to-server video push";
  McpttInfo info;
  info.session_type = session_type;
  info.client_id = client_id();
  info.video_pull_url = url;
  call.dialog = sip_.invite(
      mcvideo_service_uri(), mcvideo_initial_offer(call), {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}},
      [this, generation = call.generation](const SipResponse &response) {
        mcvideo_invite_answered(generation, response);
      },
      headers);
  event("mcvideo_server_call_requested", {{"session_type", session_type}, {"url", url}});
}

} // namespace mcx
