//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Ad hoc group calls: call setup, participant modification and the ad hoc group emergency alert (MCPTT client side).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 17.2.2.1.1: the MCPTT client originates an ad hoc group call (on-demand session) with a list of
// MCPTT IDs or functional aliases (step 11), participant criteria (step 12) or the group of an ad hoc group emergency
// alert (step 10 NOTE 1), optionally broadcast (step 9), emergency (step 15) or imminent peril (step 16).
void Client::adhoc_call(const std::string &arguments) {
  if (!authorized_ || !dialog_.empty()) throw std::runtime_error("ad hoc call requires authorized idle state");
  if (protected_media() || protected_floor())
    throw std::runtime_error(
        "end-to-end security for ad hoc group calls is not implemented; use a clear-media profile");
  // adhoc [broadcast] [emergency|imminent] <MCPTT ID or functional alias>...
  //   or adhoc [emergency|imminent] criteria <criteria>
  bool info_broadcast = false, alert_group = false;
  std::vector<std::string> targets;
  std::istringstream words(arguments);
  std::string priority = "normal", criteria;
  for (std::string target; words >> target;) {
    if (targets.empty() && priority == "normal" && (target == "emergency" || target == "imminent"))
      priority = target;
    else if (targets.empty() && target == "broadcast")
      info_broadcast = true;
    else if (targets.empty() && target == "criteria") {
      std::getline(words >> std::ws, criteria);
      break;
    } else if (targets.empty() && target == "alert-group")
      alert_group = true;
    else
      targets.push_back(target);
  }
  require_profile({"allow-adhoc-group-call"});
  if (priority == "emergency") require_profile({"allow-emergency-adhoc-group-call"});
  if (priority == "imminent") require_profile({"allow-imminent-peril-adhoc-group-call"});

  // TS 24.379 clause 17.2.2.1.1 step 10 NOTE 1: a call following an ad hoc emergency alert names the alert's group.
  std::string alert_group_id;
  if (alert_group) {
    alert_group_id = !own_alert_group_.empty()        ? own_alert_group_
                     : received_adhoc_alerts_.empty() ? std::string{}
                                                      : received_adhoc_alerts_.begin().key();
    if (alert_group_id.empty()) throw std::runtime_error("no ad hoc emergency alert group known");
  } else if (targets.empty() && criteria.empty())
    throw std::runtime_error("ad hoc call requires MCPTT IDs or participant criteria");
  McpttInfo info;
  info.session_type = "adhoc";
  info.client_id = client_id();
  info.functional_alias = functional_alias_;
  if (info_broadcast) info.broadcast = true;
  adhoc_broadcast_ = info_broadcast;
  if (alert_group) {
    info.request_uri = alert_group_id;
    info.adhoc_emergency_alert_group = true;
  }
  std::vector<SipHeader> headers;
  if (priority != "normal") {
    // TS 24.379 clause 17.2.2.1.1 steps 15 and 16: <adhoc-emergency-ind> or <imminentperil-ind> with
    // Resource-Priority.
    McpttInfo level;
    if (priority == "emergency") {
      info.adhoc_emergency = true;
      level.emergency = true;
    } else {
      info.imminent_peril = true;
      level.imminent_peril = true;
    }
    headers = priority_headers(level);
  }
  adhoc_priority_ = priority;
  adhoc_criteria_ = criteria;
  info.participant_criteria = criteria; // TS 24.379 clause 17.2.2.1.1 step 12
  auto parts = info_parts(info);
  if (!targets.empty()) parts.push_back({resource_lists_mime, resource_list_xml(targets)});
  // TS 24.379 clause 17.2.2.1.1 step 14: "if an implicit floor request is required".
  const auto implicit = implicit_choice();
  prepare_implicit_speech(implicit);
  local_.floor_priority.reset();
  local_.floor_ssrc = random_ssrc();
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  call_type_ = "adhoc";
  call_group_.clear();
  status_["last_call_failure"] = nullptr;
  invite_with_implicit(implicit.requested, true, [&] {
    dialog_ = sip_.invite(
        service(), local_, parts,
        [this](const SipResponse &response) {
          if (response.status_code >= 200 && response.status_code < 300) {
            // The server returns the generated ad hoc group identity (TS 24.379 clause 17.2.2.1.1, 2xx step 4).
            for (const auto &part : response.parts)
              if (part.content_type == mcptt_mime) call_group_ = parse_mcptt_info(part.body).calling_group;
            remember_session(response.headers);
          } else if (response.status_code >= 300) {
            status_["last_call_failure"] = {{"status", response.status_code},
                                            {"warning", std::string(get_header(response.headers, "Warning"))}};
            adhoc_priority_ = "normal";
            failure("ad hoc call setup: " + std::to_string(response.status_code));
          }
        },
        headers);
  });
  status_["call_state"] = "calling";
  event("adhoc_call_requested", {{"targets", targets}, {"priority", priority}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 12.1A.1.1 and clause 12.1A.1.2: the MCPTT client originates or cancels an ad hoc group
// emergency alert (SIP MESSAGE) and tracks the ad hoc emergency alert state (AH-MEA). Command:
//   adhoc-alert <criteria>   or   adhoc-alert cancel
void Client::adhoc_alert(const std::string &arguments) {
  if (!authorized_) throw std::runtime_error("ad hoc alert requires service authorization");
  McpttInfo info;
  info.client_id = client_id();
  const bool cancel = arguments == "cancel";
  if (cancel) {
    if (own_alert_group_.empty()) throw std::runtime_error("no ad hoc alert to cancel");
    info.request_uri = own_alert_group_;
    info.adhoc_alert = false;
    // TS 24.379 clause 12.1A.1.2 step 4 f): cancel pending until the receipt (clause 12.1A.1.3) arrives.
    ah_mea_ = 4;
  } else {
    if (arguments.empty()) throw std::runtime_error("usage: adhoc-alert <criteria> | adhoc-alert cancel");
    require_profile({"allow-activate-adhoc-group-emergency-alert"});
    info.adhoc_alert = true;
    info.participant_criteria = arguments;
    ah_mea_ = 2;
  }
  auto parts = info_parts(info);
  if (!cancel && cfg_.at("mcptt").contains("emergency_location"))
    parts.push_back({mcptt_location_mime, emergency_location_xml(configured_emergency_location())});
  const auto boundary = "mcx-" + random_id();
  const auto [type, body] = message_body(parts, boundary);
  sip_.request(
      "MESSAGE", service(), type, body,
      {{"P-Preferred-Service", mcptt_icsi},
       {"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"},
       {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"}},
      [this, cancel](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["last_adhoc_alert_result"] = {{"status", response.status_code},
                                              {"warning", std::string(get_header(response.headers, "Warning"))}};
        if (response.status_code >= 300) {
          // TS 24.379 clause 12.1A.1.2: a refused cancellation leaves the alert initiated.
          if (!cancel) ah_mea_ = 1;
          if (cancel && ah_mea_ == 4) ah_mea_ = 3;
          failure("ad hoc alert: " + std::to_string(response.status_code));
          return;
        }
        // TS 24.379 clause 12.1A.1.2: the 2xx leaves the cancellation pending; the receipt ends it.
        if (cancel) return;
        for (const auto &part : response.parts)
          if (part.content_type == mcptt_mime) own_alert_group_ = parse_mcptt_info(part.body).calling_group;
        if (response.parts.empty() && !response.body.empty())
          own_alert_group_ = parse_mcptt_info(response.body).calling_group;
        ah_mea_ = 3;
      });
  event("adhoc_alert_requested", {{"cancel", cancel}, {"criteria", info.participant_criteria}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 17.2.6.1.1: the MCPTT client modifies the participant list or the participant criteria of an
// established ad hoc group call (re-INVITE). Command:
//   adhoc-modify add <MCPTT ID>... [remove <MCPTT ID>...]  or  adhoc-modify criteria <criteria>
void Client::adhoc_modify(const std::string &arguments) {
  if (call_type_ != "adhoc" || dialog_.empty() || !remote_ || status_.value("call_state", "") != "active")
    throw std::runtime_error("ad hoc modification requires an active ad hoc call");
  require_profile({"allow-to-modify-adhoc-group-call-participants-info"});
  if (priority_pending_) throw std::runtime_error("call modification already pending");
  McpttInfo info;
  info.session_type = "adhoc";
  info.client_id = client_id();
  info.calling_group = call_group_;
  std::vector<std::string> entries;
  std::istringstream words(arguments);
  std::string word, method;
  while (words >> word) {
    if (entries.empty() && method.empty() && word == "criteria") {
      std::getline(words >> std::ws, info.participant_criteria);
      break;
    }
    if (word == "add")
      method = "INVITE";
    else if (word == "remove")
      method = "BYE";
    else if (method.empty())
      throw std::runtime_error("usage: adhoc-modify add|remove <MCPTT ID>... or adhoc-modify criteria <criteria>");
    else
      entries.push_back(word + ";method=" + method);
  }
  if (entries.empty() == info.participant_criteria.empty())
    throw std::runtime_error("modify either the participant list or the criteria");
  // Step 1: the mcptt-info as the session is established: a list change of a call by criteria carries its criteria
  // (unchanged, so not a criteria change).
  const bool new_criteria = entries.empty();
  if (!new_criteria) info.participant_criteria = adhoc_criteria_;
  auto parts = info_parts(info);
  if (!entries.empty()) parts.push_back({resource_lists_mime, resource_list_xml(entries)});
  priority_pending_ = true;
  try {
    sip_.reinvite(dialog_, local_, parts, [this, new_criteria, criteria = info.participant_criteria](
                                              const SipResponse &response) {
      if (response.status_code < 200) return;
      priority_pending_ = false;
      if (new_criteria && response.status_code < 300) adhoc_criteria_ = criteria;
      status_["last_modify_result"] = {{"status", response.status_code},
                                       {"warning", std::string(get_header(response.headers, "Warning"))}};
      if (response.status_code >= 300) failure("ad hoc modification: " + std::to_string(response.status_code));
    });
  } catch (...) {
    priority_pending_ = false;
    throw;
  }
  event("adhoc_modify_requested", {{"entries", entries}, {"criteria", info.participant_criteria}});
}

} // namespace mcx
