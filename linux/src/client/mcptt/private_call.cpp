// Outgoing private calls (with or without floor control, emergency, to a functional alias), first-to-answer calls
// and ambient listening calls of the MCPTT client (on-demand session).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1: the MCPTT client originates an on-demand private call (clause 11.1.2.2 without floor
// control, clause 6.2.8.3.2 as an emergency private call) and handles the responses: 2xx, 300 for a functional alias,
// 488 with a KMS Redirect Response (TS 33.180 clause 5.2.8.2.5) and failures; it also reports the outcome of a
// remote, transfer or forwarding request.
void Client::private_call(const std::string &arguments, bool to_alias) {
  if (!authorized_ || !dialog_.empty()) throw std::runtime_error("private call requires authorized idle state");
  // private <MCPTT ID> [auto|manual|force-auto] [emergency [alert]]
  std::istringstream words(arguments);
  std::string target, mode, word;
  words >> target;
  bool emergency = false, alert = false, tamper = false, without_floor = false;
  std::string kms;
  while (words >> word) {
    // TS 24.379 clause 11.1.2.2: a private call without floor control.
    if (word == "no-floor" && !without_floor) {
      without_floor = true;
      continue;
    }
    // Test aid: "tamper-key" alters the signed I_MESSAGE, to exercise the callee's
    // 488 with warning 136 (TS 24.379 clause 11.1.1.2.1.2 step 4d).
    if (word == "tamper-key" && protected_media() && !tamper) {
      tamper = true;
      continue;
    }
    if (word == "emergency" && !emergency)
      emergency = true;
    else if (word == "alert" && emergency && !alert)
      alert = true;
    // Test aid: key the PCK to a given KMS (TS 33.180 clause 5.2.8.2.5), e.g. a wrong one.
    else if (word.starts_with("kms="))
      kms = word.substr(4);
    else if (mode.empty() && !emergency && (word == "auto" || word == "manual" || word == "force-auto"))
      mode = word;
    else
      throw std::runtime_error("usage: private <MCPTT ID> [auto|manual|force-auto] [emergency [alert]] [no-floor]");
  }
  if (mode.empty()) mode = cfg_.at("mcptt").value("private_call_mode", std::string("auto"));
  if (target.empty() || target == user()) throw std::runtime_error("private call requires another MCPTT ID");
  require_profile({"allow-private-call", mode == "auto"     ? "allow-automatic-commencement"
                                         : mode == "manual" ? "allow-manual-commencement"
                                                            : "allow-force-auto-answer"});
  if (emergency) require_profile({"allow-emergency-private-call"});
  if (to_alias && emergency) throw std::runtime_error("emergency calls to a functional alias are not implemented");
  mode_for_redirect_ = mode;
  std::vector<SipHeader> headers;
  if (mode == "auto")
    headers = {{"Answer-Mode", "Auto"}};
  else if (mode == "manual")
    headers = {{"Answer-Mode", "Manual"}};
  else if (mode == "force-auto")
    headers = {{"Priv-Answer-Mode", "Auto"}};
  else
    throw std::runtime_error("private call mode must be auto, manual or force-auto");
  // Set only once the request is accepted, so that a refused command leaves no override for the next call.
  if (!kms.empty()) forwarded_kms_ = {target, kms};
  auto parts = private_call_parts(target, to_alias, emergency, alert, headers);
  if (emergency) {
    mepc_ = 2;
    mepp_ = 4;
    if (alert) mpea_ = 2;
  }
  local_.floor_priority.reset();
  local_.floor_ssrc = random_ssrc();
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  // TS 24.379 clause 11.1.2.2 (steps 12 and 13 of clause 11.1.1.2.1.1): no media-floor control section, hence no floor parameters;
  // the speech is sent with the SSRC the SDP announces.
  local_.control = without_floor ? MediaControl::none : MediaControl::floor;
  if (without_floor) {
    local_.queueing = false;
    local_.audio_ssrc = random_ssrc();
  }
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  call_type_ = "private";
  private_peer_ = target;
  status_["last_call_failure"] = nullptr;
  // A functional alias is resolved by a 300 before any media: the PCK goes to the
  // MCPTT ID selected for it (step 11d with clause 6.2.8.3.9).
  const auto key = to_alias ? std::nullopt : offer_private_key(target);
  if (tamper && !local_.key_mgmt.empty()) {
    auto &character = local_.key_mgmt[local_.key_mgmt.size() / 2];
    character = character == 'A' ? 'B' : 'A';
  }
  dialog_ = sip_.invite(
      service(), local_, parts,
      [this, target](const SipResponse &response) { private_call_response(target, response); }, headers);
  bind_private_key(key, true);
  status_["call_state"] = "calling";
  event("private_call_requested", {{"target", target}, {"mode", mode}, {"emergency", emergency}, {"alert", alert}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1 (clause 6.2.8.3.2 for an emergency private call): the MCPTT information, priority
// and Replaces headers and the resource list of a private call request.
std::vector<BodyPart> Client::private_call_parts(const std::string &target, bool to_alias, bool emergency, bool alert,
                                                 std::vector<SipHeader> &headers) {
  McpttInfo info;
  info.session_type = "private";
  info.client_id = client_id();
  info.calling_user = user();
  info.functional_alias = functional_alias_;
  if (to_alias) info.call_to_functional_alias = true; // TS 24.379 clause 11.1.1.2.1.1 step 14c-ii-A
  info.called_functional_alias = std::exchange(pending_called_alias_, std::string{});
  info.service = std::exchange(next_call_service_, {});
  if (emergency) {
    // TS 24.379 clause 6.2.8.3.2: emergency-ind true, alert-ind true or false, Resource-Priority.
    info.emergency = true;
    info.alert = alert;
    for (const auto &header : priority_headers(info)) headers.push_back(header);
  } else if (auto priority = std::exchange(next_call_priority_, {}); !priority.empty()) {
    headers.push_back({"Resource-Priority", priority});
    event("ric_priority_applied", {{"target", target}, {"resource_priority", priority}});
  }
  // TS 24.379 clause 11.1.1.2.1.1 step 14b1: a transfer call replaces the requester's call with the target.
  if (auto replaces = std::exchange(next_call_replaces_, {}); !replaces.empty())
    headers.push_back({"Replaces", replaces});
  auto parts = info_parts(info);
  parts.push_back({resource_lists_mime, private_call_resource_list(target)});
  if (alert) parts.push_back({mcptt_location_mime, emergency_location_xml(configured_emergency_location())});
  return parts;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1: the responses to a private call request: 2xx, 300 for a functional alias, 488
// with a KMS Redirect Response (TS 33.180 clause 5.2.8.2.5) and failures; the outcome of a remote, transfer or
// forwarding request (clause 11.1.8.2.2, clause 11.1.9.2.2).
void Client::private_call_response(const std::string &target, const SipResponse &response) {
  if (followup_ && followup_->target == target && response.status_code > 100) {
    // TS 24.379 clause 11.1.8.2.2 and clause 11.1.9.2.2: ringing, progress, 2xx or 480/175 mean success.
    const bool forwarded =
        response.status_code == 480 && client_rules::has_warning(get_header(response.headers, "Warning"), 175);
    if (response.status_code < 300 || forwarded)
      report_followup(true);
    else
      report_followup(false);
  }
  if (response.status_code >= 200 && response.status_code < 300) {
    private_call_answered(target, response);
  } else if (response.status_code == 300) {
    private_call_alias_redirect(response);
  } else if (response.status_code == 488 && krr_retries_ == 0 && keys_ && krr_body(response)) {
    private_call_kms_redirect(target, response);
  } else if (response.status_code >= 300) {
    private_emergency_refused(); // TS 24.379 clause 6.2.8.3.5
    status_["last_call_failure"] = {{"status", response.status_code},
                                    {"warning", std::string(get_header(response.headers, "Warning"))}};
    failure("private call setup: " + std::to_string(response.status_code));
    krr_retries_ = 0;
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1: a 2xx response to the private call request; with clause 11.1.8.2.3 (announced
// transfer), clause 11.1.5.2.3 (call-back) and clause 6.2.8.3.4 (emergency private call state).
void Client::private_call_answered(const std::string &target, const SipResponse &response) {
  krr_retries_ = 0;
  remember_session(response.headers);
  // TS 24.379 clause 11.1.8.2.3 steps 2b to 2d: the target's dialog for the transferred user's call.
  if (held_ && target == announced_target_) {
    for (const auto &part : response.parts)
      if (part.content_type == mcptt_mime) {
        const auto answer = parse_mcptt_info(part.body);
        if (const auto value = answer.service.find("replaces-header-value"); value != answer.service.end())
          announced_replaces_ = value->second;
      }
    status_["announced_transfer"] = {{"state", "announcing"},
                                     {"target", target},
                                     {"transferred", held_->private_peer},
                                     {"replaces", !announced_replaces_.empty()}};
    event("announced_transfer_target_answered", {{"target", target}, {"replaces", announced_replaces_}});
  }
  // TS 24.379 clause 11.1.5.2.3: a fulfilled call-back removes the target entry.
  if (pccb_target_.contains(target)) pccb_target_.erase(target);
  private_emergency_granted(response); // TS 24.379 clause 6.2.8.3.4
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.3.4: a 2xx to an emergency private call request (MEPC 2): MEPP 2 and MEPC 3, and the
// alert initiated (MPEA 3) unless warning 149 says a SIP INFO request follows.
void Client::private_emergency_granted(const SipResponse &response) {
  if (mepc_ != 2) return;
  mepp_ = 2;
  mepc_ = 3;
  if (mpea_ == 2 && !info_pending(response)) {
    mpea_ = 3;
    private_alerts_.insert(user());
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.3.5: an emergency private call request refused or abandoned: back to MEPC 1, MEPP 1 (from
// confirm-pending) and MPEA 1.
void Client::private_emergency_refused() {
  if (mepc_ == 1) return;
  mepc_ = 1;
  if (mepp_ == 4) mepp_ = 1;
  mpea_ = 1;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1: call the MCPTT ID selected for the functional alias.
void Client::private_call_alias_redirect(const SipResponse &response) {
  std::string selected, called;
  for (const auto &part : response.parts)
    if (part.content_type == mcptt_mime) {
      const auto redirect = parse_mcptt_info(part.body);
      selected = redirect.request_uri;
      called = redirect.called_functional_alias;
    }
  if (selected.empty()) {
    failure("functional alias redirect without an MCPTT ID");
    return;
  }
  status_["functional_alias_redirect"] = {{"alias", called}, {"selected", selected}};
  schedule([this, selected, called, mode = mode_for_redirect_] {
    if (!dialog_.empty()) return;
    pending_called_alias_ = called;
    private_call(selected + " " + mode);
  });
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.2.8.2.5: a KMS Redirect Response; key the PCK again to the
// first receiver KMS listed that this client holds a certificate for.
void Client::private_call_kms_redirect(const std::string &target, const SipResponse &response) {
  private_emergency_refused();
  try {
    const auto redirect = keying::parse_krr(*krr_body(response));
    const auto chosen = std::find_if(redirect.receiver_list.begin(), redirect.receiver_list.end(),
                                     [&](const std::string &kms) { return keys_->knows_kms(kms); });
    status_["last_krr"] = {{"creator", redirect.creator},
                           {"receiver_kms", redirect.receiver_kms},
                           {"receiver_list", redirect.receiver_list},
                           {"chosen", chosen == redirect.receiver_list.end() ? "" : *chosen}};
    event("kms_redirect_received", status_["last_krr"]);
    if (chosen == redirect.receiver_list.end()) throw std::runtime_error("no acceptable receiver KMS");
    ++krr_retries_;
    schedule([this, target, kms = *chosen, mode = mode_for_redirect_] {
      if (!dialog_.empty()) return;
      forwarded_kms_ = {target, kms};
      try {
        private_call(target + " " + mode);
      } catch (const std::exception &error) {
        failure(error.what());
      }
    });
  } catch (const std::exception &error) {
    status_["last_call_failure"] = {{"status", response.status_code}, {"warning", ""}};
    failure(std::string("KMS Redirect Response: ") + error.what());
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1 steps 10 and 15: the MCPTT client originates a first-to-answer call to two or more
// invitees (or one functional alias); the answering MCPTT ID arrives in the 200 (OK).
void Client::first_to_answer_call(const std::string &arguments, bool to_alias) {
  if (!authorized_ || !dialog_.empty()) throw std::runtime_error("first-to-answer call requires authorized idle state");
  std::vector<std::string> targets;
  std::istringstream words(arguments);
  bool emergency = false, alert = false;
  for (std::string target; words >> target;) {
    // Trailing keywords request an emergency first-to-answer call (TS 24.379 clause 6.2.8.3.2).
    if (target == "emergency") {
      emergency = true;
      continue;
    }
    if (target == "alert") {
      alert = true;
      continue;
    }
    if (target == user()) throw std::runtime_error("first-to-answer call cannot invite the caller");
    if (std::find(targets.begin(), targets.end(), target) == targets.end()) targets.push_back(target);
  }
  require_profile({"allow-private-call", "allow-request-first-to-answer-call"});
  if (to_alias ? targets.size() != 1 : targets.size() < 2)
    throw std::runtime_error(to_alias ? "first-to-answer call to a functional alias takes one alias"
                                      : "first-to-answer call requires two or more MCPTT IDs");
  McpttInfo info;
  info.session_type = "first-to-answer";
  info.client_id = client_id();
  info.calling_user = user();
  info.functional_alias = functional_alias_;
  if (to_alias) info.call_to_functional_alias = true;
  if (alert && !emergency) throw std::runtime_error("alert requires an emergency first-to-answer call");
  if (to_alias && emergency) throw std::runtime_error("emergency calls to a functional alias are not implemented");
  std::vector<SipHeader> headers;
  if (emergency) {
    info.emergency = true;
    info.alert = alert;
    for (const auto &header : priority_headers(info)) headers.push_back(header);
  }
  auto parts = info_parts(info);
  parts.push_back({resource_lists_mime, resource_list_xml(targets)});
  if (alert) parts.push_back({mcptt_location_mime, emergency_location_xml(configured_emergency_location())});
  if (emergency) {
    mepc_ = 2;
    mepp_ = 4;
    if (alert) mpea_ = 2;
  }
  local_.floor_priority.reset();
  local_.floor_ssrc = random_ssrc();
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  call_type_ = "private";
  first_to_answer_ = true;
  private_peer_.clear();
  status_["last_call_failure"] = nullptr;
  // TS 33.180 clause 7.2.4.2: no key material in the request; the PCK arrives in the answer
  // from one of the invitees (any user, when a functional alias chooses).
  local_.key_mgmt.clear();
  awaiting_answer_key_ = protected_media();
  answer_key_senders_ = to_alias ? std::vector<std::string>{} : targets;
  dialog_ = sip_.invite(
      service(), local_, parts,
      [this](const SipResponse &response) {
        if (response.status_code == 183 && status_.value("call_state", "") == "calling")
          status_["call_state"] = "progress";
        if (response.status_code >= 200 && response.status_code < 300) {
          for (const auto &part : response.parts)
            if (part.content_type == mcptt_mime) private_peer_ = parse_mcptt_info(part.body).called_party;
          remember_session(response.headers);
          private_emergency_granted(response); // TS 24.379 clause 6.2.8.3.4
          event("first_to_answer_answered", {{"called_party", private_peer_}});
        } else if (response.status_code >= 300) {
          private_emergency_refused(); // TS 24.379 clause 6.2.8.3.5
          status_["last_call_failure"] = {{"status", response.status_code},
                                          {"warning", std::string(get_header(response.headers, "Warning"))}};
          failure("first-to-answer call setup: " + std::to_string(response.status_code));
        }
      },
      headers);
  status_["call_state"] = "calling";
  event("first_to_answer_requested", {{"targets", targets}, {"emergency", emergency}, {"alert", alert}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.6.2.1.1: the MCPTT client originates an on-demand ambient listening call. Command:
//   ambient remote <MCPTT ID>   listen to that user (remote-init)
//   ambient local <MCPTT ID>    be listened to by that user (local-init)
void Client::ambient_call(const std::string &arguments) {
  std::istringstream words(arguments);
  std::string kind, target, extra;
  words >> kind >> target >> extra;
  if ((kind != "remote" && kind != "local") || target.empty() || !extra.empty())
    throw std::runtime_error("usage: ambient remote|local <MCPTT ID>");
  if (!authorized_ || !dialog_.empty())
    throw std::runtime_error("ambient listening call requires authorized idle state");
  if (target == user()) throw std::runtime_error("ambient listening call requires another MCPTT ID");
  require_profile({kind == "remote" ? "allow-request-remote-initiated-ambient-listening"
                                    : "allow-request-locally-initiated-ambient-listening"});
  const auto type = kind + "-init";
  const bool listening = kind == "remote";
  McpttInfo info;
  info.session_type = "ambient-listening"; // step 7
  info.client_id = client_id();
  info.calling_user = user();
  info.ambient_listening_type = type; // step 8
  auto parts = info_parts(info);
  parts.push_back({resource_lists_mime, private_call_resource_list(target)}); // step 9
  local_.floor_priority.reset();
  local_.floor_ssrc = random_ssrc();
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  // Steps 11 to 13 and clause 6.2.1 step c: an implicit floor request (local-init) or an
  // implicit request to grant the floor to the invited user (remote-init).
  local_.implicit_request = true;
  local_.direction = listening ? "recvonly" : "sendonly";
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  if (!listening) {
    floor_timers_.expect_implicit_grant();
    transmit_ = ambient_audio();
    transmit_offset_ = 0;
  }
  call_type_ = "ambient";
  private_peer_ = target;
  ambient_ = AmbientCall{"", type, listening ? "listening" : "listened-to", target, false, false, ""};
  status_["last_call_failure"] = nullptr;
  const auto key = offer_private_key(target);
  dialog_ = sip_.invite(
      service(), local_, parts,
      [this](const SipResponse &response) {
        if (response.status_code >= 300) {
          status_["last_call_failure"] = {{"status", response.status_code},
                                          {"warning", std::string(get_header(response.headers, "Warning"))}};
          failure("ambient listening call setup: " + std::to_string(response.status_code));
        }
        if (!ambient_) return;
        if (response.status_code == 183 && get_header(response.headers, "Alert-Info") == "<file:///dev/null>")
          ambient_->null_alert = true;
        if (response.status_code >= 200 && response.status_code < 300) {
          ambient_->release_allowed =
              get_header(response.headers, "Feature-Caps").find("g.3gpp.mcptt.ambient-listening-call-release") !=
              std::string_view::npos;
          event("ambient_call_answered", {{"role", ambient_->role}, {"release_allowed", ambient_->release_allowed}});
        }
      },
      {{"Priv-Answer-Mode", "Auto"}}); // step 14
  bind_private_key(key, true);
  ambient_->dialog = dialog_;
  // TS 24.379 clause 11.1.6.2.0: only the listening user sees the call being set up.
  status_["call_state"] = listening ? "calling" : "idle";
  event("ambient_call_requested", {{"type", type}, {"target", target}});
}

} // namespace mcx
