//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Call state, the on-network floor participant's messages and the received media of an MCPTT call.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19),
// TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

#include <arpa/inet.h>

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.1: check the server's SDP against this client's offer (floor control, SSRC, media profile,
// keys, media address); throws when it does not fit. TS 24.380 clauses 14.3.2 and 14.4: an "mc_queueing" or
// "mc_implicit_request" this client did not offer is no reason to refuse it: an answer's is discarded
// (negotiated_media), and an offer's is answered without it.
void Client::check_remote(const MediaDescription &media, bool private_call) {
  if ((media.control == MediaControl::none || media.no_floor_control) != no_floor())
    throw std::runtime_error("floor control negotiation mismatch");
  // TS 24.380 clause 4.3.3.1: the server's SSRC for this client's floor control messages; refused when it is this
  // client's own or, under the CSK, one already used for another stream, also of the held call that has carried no
  // packet yet (mcx::floor_ssrc_refused, mcptt_floor_streams; RFC 7714 section 8.4, RFC 3711 section 9.1); the call's
  // own SSRC again (a re-INVITE, a refresh) is the stream going on.
  if (!no_floor() &&
      floor_ssrc_refused(media.floor_ssrc, local_.floor_ssrc,
                         remote_ ? std::optional(remote_->floor_ssrc) : std::nullopt,
                         protected_floor() ? &protection_ : nullptr, mcptt_floor_streams()))
    throw std::runtime_error("directional floor SSRC collision");
  if (media.protected_media != protected_media()) throw std::runtime_error("unsupported media profile");
  // A private call's key arrives with the call itself (TS 33.180 clause 7.2.2).
  if (protected_media() && !private_call && !protection_.group_ready())
    throw std::runtime_error("group key unavailable");
  if (protected_floor() && !protection_.floor_ready()) throw std::runtime_error("floor key unavailable");
  if (media.address != resolve_ipv4(cfg_.at("server_address").get<std::string>()) ||
      media.floor_address != media.address)
    throw std::runtime_error("media server address mismatch");
}

//------------------------------------------------------------------------------
// TS 24.379 clauses 11.1.6.2.0 and 11.1.6.2.1.2 6): a floor event of the call; that of a listened-to client's hidden
// ambient listening call is no user indication and is reported as "ambient_<kind>".
void Client::floor_event(std::string_view kind, const Json &detail) {
  if (ambient_hidden())
    event("ambient_" + std::string(kind), detail);
  else
    event(kind, detail);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 14.4: "if the attribute contains attribute parameters that were not present in the associated
// offer, the offerer shall discard those attribute parameters": "mc_queueing" without this client's, and
// "mc_implicit_request" unless this client's INVITE requested the floor with it (implicit_offered_). The "mc_ssrc"
// is kept: the offerer "shall use the returned value [...] even if the "mc_ssrc" attribute was not present in the
// associated offer". For a server's offer this is the negotiated outcome as well (clause 14.3.2).
MediaDescription Client::negotiated_media(MediaDescription media) const {
  media.queueing = client_rules::answer_parameter_kept(media.queueing, local_.queueing);
  media.implicit_request = client_rules::answer_parameter_kept(media.implicit_request, implicit_offered_);
  // TS 24.380 clause 14.3.4: "mc_granted" in the answer grants the floor only when this client's offer had it.
  media.granted = client_rules::answer_parameter_kept(media.granted, granted_offered_);
  return media;
}

//------------------------------------------------------------------------------
// The call command's implicit floor request choice (TS 24.380 clause 14.2.5), the configured one otherwise:
// mcptt.implicit_floor_request (default false) with mcptt.implicit_wav. Consumed by the call it is for.
Client::ImplicitChoice Client::implicit_choice() {
  if (auto choice = std::exchange(next_implicit_, std::nullopt)) return *choice;
  const auto &mcptt = cfg_.at("mcptt");
  return {mcptt.value("implicit_floor_request", false), mcptt.value("implicit_wav", std::string{})};
}

//------------------------------------------------------------------------------
// Helper: the talk burst of an implicit floor request: the choice's WAV, sent once Floor Granted arrives
// (floor_packet), as for "ptt <WAV>".
void Client::prepare_implicit_speech(const ImplicitChoice &choice) {
  if (!choice.requested) return;
  if (choice.wav.empty()) throw std::runtime_error("implicit floor request needs implicit=<WAV> or mcptt.implicit_wav");
  wav_path_ = choice.wav;
  transmit_ = read_wav(wav_path_);
  transmit_offset_ = 0;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.4 and TS 24.380 clause 14.2.5: the call's INVITE (invite) carries "mc_implicit_request" when
// implicit; only this INVITE does (TS 24.380 clause 14.5: in a later offer it has meaning only for an upgrade to
// emergency). own: it is this client's implicit floor request, handled at the 200 (OK); false for the implicit
// request to grant the floor to the terminating client of a remotely initiated ambient listening call.
void Client::invite_with_implicit(bool implicit, bool own, const std::function<void()> &invite) {
  early_floor_.clear();
  call_answered_ = false;
  local_.implicit_request = implicit;
  // TS 24.380 clause 14.2.4: "mc_granted" in the offer of the initial INVITE when this client accepts the grant in the
  // 200 (OK) (mcptt.offer_granted, default false), with its own implicit floor request.
  local_.granted = implicit && own && cfg_.at("mcptt").value("offer_granted", false);
  try {
    invite();
  } catch (...) {
    local_.implicit_request = implicit_offered_ = local_.granted = granted_offered_ = false;
    throw;
  }
  granted_offered_ = local_.granted;
  local_.implicit_request = local_.granted = false;
  implicit_offered_ = implicit && own;
  // The outcome of the last implicit floor request: "offered", then "accepted" or "not accepted".
  if (implicit_offered_) status_["implicit_floor_request"] = "offered";
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.4 (a re-INVITE upgrading the call to an emergency group or private call, or to an imminent peril
// group call, with "mc_implicit_request" is an implicit floor request; clauses 10.1.1.2.1.3, 10.1.2.2.1.3,
// 11.1.1.2.1.5 4); TS 24.380 clauses 14.2.5, 14.5): with the command's "implicit[=<WAV>]" (take_implicit_choice), the
// upgrade's offer carries the attribute and the WAV waits for the grant, as for a call's INVITE. True when it does;
// the caller sends the re-INVITE with local_ and then clears local_.implicit_request. The floor messages for this
// client before the re-INVITE's answer are stored as for the INVITE (TS 24.380 clause 6.2.4.2.2 step 2).
bool Client::upgrade_implicit_begin() {
  const auto choice = std::exchange(next_implicit_, std::nullopt);
  if (!choice || !choice->requested) return false;
  if (no_floor()) throw std::runtime_error("an implicit floor request needs floor control in the call");
  if (talking_ || requesting_) throw std::runtime_error("the floor is already held or requested");
  prepare_implicit_speech(*choice);
  granted_offered_ = false; // TS 24.380 clause 14.5: no "mc_granted" in a later offer
  early_floor_.clear();
  call_answered_ = false;
  implicit_offered_ = true;
  local_.implicit_request = true;
  status_["implicit_floor_request"] = "offered";
  return true;
}

// The final response to the upgrade's re-INVITE: a 2xx completes it (implicit_floor_answered, with the answer's
// "mc_implicit_request" once its SDP is in), a refusal leaves the floor as it was.
void Client::upgrade_implicit_answered(int status_code) {
  if (!implicit_offered_ || call_answered_) return;
  if (status_code >= 300) {
    implicit_offered_ = false;
    call_answered_ = true;
    early_floor_.clear();
    transmit_.clear();
    transmit_offset_ = 0;
    status_["implicit_floor_request"] = "not accepted";
    event("implicit_floor_request_not_accepted", {{"dialog", dialog_}, {"status", status_code}});
    return;
  }
  call_answered_ = true;
  implicit_floor_answered();
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.2.2 (originating floor participant), once both the 200 (OK) and its SDP answer are in:
// with the implicit floor request negotiated (clause 6.2.1 item 1; the answer's "mc_implicit_request", clause
// 14.3.5), step 4: "a. shall start timer T101 (Floor Request) and initialise counter C101 (Floor Request) to 1;
// b. shall enter the 'U: pending Request' state; and c. [...] shall act as if the floor control message was
// received in the 'U: pending Request' state" for each one stored before the 200 (OK) (step 2). Without it the
// client stays in 'U: has no permission' and does not send the talk burst.
void Client::implicit_floor_answered() {
  if (!implicit_offered_ || !call_answered_ || !remote_) return;
  implicit_offered_ = false;
  auto stored = std::exchange(early_floor_, {});
  if (!remote_->implicit_request) {
    requesting_ = false;
    transmit_.clear();
    transmit_offset_ = 0;
    status_["implicit_floor_request"] = "not accepted";
    event("implicit_floor_request_not_accepted", {{"dialog", dialog_}});
    return;
  }
  floor_timers_.expect_implicit_grant(floor_message(FloorType::Request), monotonic_ms());
  requesting_ = true;
  status_["implicit_floor_request"] = "accepted";
  // TS 24.380 clause 14.3.4 and the "mc_granted" semantics of clause 12.1 (NOTE 3): the answer's "mc_granted" grants
  // the floor; the participant has permission as on a Floor Granted (clause 6.2.4.3.2), with the answer's "mc_ssrc".
  if (remote_->granted) {
    FloorPacket granted;
    granted.type = FloorType::Granted;
    granted.ssrc = remote_->floor_ssrc;
    floor_timers_.receive(granted, monotonic_ms());
    audio_ssrc_ = remote_->granted_audio_ssrc ? remote_->granted_audio_ssrc : local_.audio_ssrc;
    talking_ = true;
    requesting_ = false;
    next_audio_ = monotonic_ms();
    status_["implicit_floor_request"] = "granted";
    floor_event("floor_granted", {{"by", "mc_granted"}});
  }
  event("implicit_floor_request_accepted", {{"dialog", dialog_}, {"stored_floor_messages", stored.size()}});
  for (const auto &datagram : stored) {
    try {
      floor_packet(datagram);
    } catch (const std::exception &error) {
      failure(std::string("stored floor message: ") + error.what());
    }
  }
}

//------------------------------------------------------------------------------
// Helper: a SIP dialog was confirmed or ended: route MCData dialogs to their own state, make an MCPTT call active
// (and subscribe to its conference event package, TS 24.379 clause 10.1.3.2) or clear its state on release
// (TS 24.380 clause 6.2.2).
void Client::call_state(std::string_view id, SipCallState state, int code) {
  // RFC 3891 section 3: the call that replaced the pre-established session is established: the session ends.
  if (!pre_session_replaced_by_.empty() && id == pre_session_replaced_by_ &&
      (state == SipCallState::confirmed || state == SipCallState::disconnected)) {
    pre_session_replaced_by_.clear();
    if (state == SipCallState::confirmed && pre_session_) {
      pre_session_->state = "releasing";
      event("pre_session_replaced", {{"session", pre_session_->dialog}, {"by", std::string(id)}});
      sip_.bye(pre_session_->dialog);
      pre_session_status();
    }
  }
  if (!ipcon_dialog_.empty() && id == ipcon_dialog_) {
    if (state == SipCallState::disconnected) {
      if (ipcon_result_.is_object() && ipcon_result_.value("state", "") == "open") ipcon_result_["state"] = "released";
      ipcon_reset();
      event("mcdata_ipconn_released", {{"dialog", std::string(id)}, {"status", code}});
    }
    return;
  }
  // TS 24.282 clause 6.2.8.4.1: an FD invitation waiting for the user, cancelled before it was answered.
  if (state == SipCallState::disconnected && mcdata_media_pending_ended(id, code)) return;
  // The MCData media plane holds its own dialog (TS 24.282 clause 9.2.3); it is not an MCPTT call.
  if (!mcdata_media_dialog_.empty() && id == mcdata_media_dialog_) {
    if (state == SipCallState::disconnected) {
      mcdata_media_dialog_.clear();
      if (mcdata_media_result_.is_object() && mcdata_media_result_.value("state", "") != "sent")
        mcdata_media_result_["state"] = "released";
      mcdata_media_.reset();
      mcdata_media_pending_.clear();
      mcdata_media_role_.clear();
      mcdata_media_session_ = false;
      event("mcdata_media_released", {{"dialog", std::string(id)}, {"status", code}});
    }
    return;
  }
  if (state == SipCallState::confirmed) {
    // TS 24.380 clause 6.2.4.2.2: the 200 (OK) of the call being set up (an implicit floor request's outcome).
    if (id == dialog_ && !call_answered_) {
      call_answered_ = true;
      implicit_floor_answered();
    }
    if (ambient_ && id == ambient_->dialog) {
      if (dialog_ != id) return; // yielded to another call
      if (ambient_->role == "listened-to") {
        // TS 24.379 clause 11.1.6.2.1.1 and clause 11.1.6.2.1.2: no indication that the call is established.
        event("ambient_call_connected", {{"dialog", id}, {"type", ambient_->type}});
        return;
      }
    }
    dialog_ = id;
    status_["call_state"] = "active";
    event("call_active", {{"dialog", id}});
    // After this dialog's response handlers, which keep the session identity the listening report names.
    schedule([this] { multicast_join(); });
    schedule([this] { subscribe_conference(); }); // automatic, with mcptt.conference_auto_subscribe
  } else if (state == SipCallState::disconnected) {
    if (held_ && id == held_->dialog) {
      // The held call ended: the transferred user left it (TS 24.379 clause 11.1.8.2.3), or, for the transferred user,
      // the requester ended it after a successful transfer (clause 11.1.8.2.1).
      held_.reset();
      transfer_kept_ = false;
      if (status_.contains("announced_transfer")) status_["announced_transfer"]["held_released"] = true;
      event("held_call_released", {{"dialog", std::string(id)}});
    }
    if (const auto key = private_keys_.find(std::string(id)); key != private_keys_.end()) {
      protection_.end_private(key->second);
      private_keys_.erase(key);
      status_.erase("private_key_id");
    }
    if (dialog_ == id) {
      clear_call_state();
    }
    if (ambient_ && id == ambient_->dialog) {
      // TS 24.379 clause 11.1.6.2.1.3 and clause 11.1.6.2.1.4: clear the cached role and type. Only a listening user
      // may be told that the call ended.
      last_ambient_ = {{"type", ambient_->type},
                       {"role", ambient_->role},
                       {"peer", ambient_->peer},
                       {"release_allowed", ambient_->release_allowed},
                       {"null_alert", ambient_->null_alert},
                       {"release_reason", ambient_->release_reason},
                       {"status", code}};
      event(ambient_->role == "listening" ? "ambient_call_terminated" : "ambient_call_cleared", last_ambient_);
      ambient_.reset();
    }
    event("call_disconnected", {{"status", code}, {"dialog", std::string(id)}});
  }
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.2 and TS 24.379 clause 6.2.8.3: the MCPTT call of this client ended: clear its dialog,
// conference subscription, media, floor and priority states, keep its type and group for a rejoin, and save the
// received audio.
void Client::clear_call_state() {
  next_floor_priority_.reset();
  private_emergency_offered_ = false;
  end_speech("call ended");
  awaiting_answer_key_ = false;
  dialog_.clear();
  pending_answer_.reset();
  priority_pending_ = false;
  if (!conference_subscription_.empty()) {
    if (retired_conferences_.size() >= 32) retired_conferences_.erase(retired_conferences_.begin());
    retired_conferences_[conference_subscription_] = session_uri_;
  }
  conference_subscription_.clear();
  conference_version_.reset();
  conference_members_.clear();
  ++conference_generation_;
  status_["conference_active"] = false;
  status_["conference_participants"] = Json::array();
  remote_.reset();
  floor_timers_ = participant_timers();
  floor_sequence_.reset();
  receive_ssrc_ = audio_ssrc_ = 0;
  talking_ = requesting_ = false;
  mix_talkers(true);
  talkers_.clear();
  media_flow_stopped_ = floor_overridden_ = floor_overriding_ = false;
  if (status_.value("media_rendering", false)) {
    status_["media_rendering"] = false;
    event("media_rendering", {{"on", false}});
  }
  transmit_.clear();
  multicast_leave(false);

  // A private session cannot be rejoined; keep only group session identities.
  if (call_type_ == "private" || call_type_ == "ambient") {
    session_uri_.clear();
    status_["session_uri"] = "";
  }
  local_.direction = "sendrecv";
  local_.implicit_request = false;
  implicit_offered_ = call_answered_ = granted_offered_ = false;
  early_floor_.clear();
  local_.control = MediaControl::floor;
  local_.no_floor_control = false;
  leave_monitored_call();
  last_call_type_ = call_type_;
  last_call_group_ = call_group_;
  call_type_.clear();
  if (!call_group_.empty()) {
    call_group_.clear();
    restore_group_key();
  }
  private_peer_.clear();
  incoming_request_uri_.clear();
  incoming_forwarding_others_.clear();

  // Cancelled before answering, or released as not selected: restore MEPP.
  if (saved_mepp_ && (fta_not_selected_ || status_.value("call_state", "") != "active")) {
    status_["private_priority_restored"] = mepp_name(*saved_mepp_);
    event("private_priority_restored", {{"mepp", mepp_name(*saved_mepp_)}, {"not_selected", fta_not_selected_}});
  }
  saved_mepp_.reset();
  fta_not_selected_ = false;
  first_to_answer_ = false;
  mepc_ = mepp_ = mpea_ = 1;
  adhoc_priority_ = "normal";
  adhoc_criteria_.clear();
  adhoc_broadcast_ = false;
  private_alerts_.clear();
  for (const auto *key : {"permission_to_request", "floor_indicator", "last_floor_reject_cause", "multi_talker",
                          "dual_floor"})
    status_.erase(key);
  status_["call_state"] = "idle";
  save_audio();
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.3.5 and clause 6.2.4.5.3: send a Floor Request (with the Location and Floor Priority fields)
// or a Floor Release, with the Floor Indicator of the call type, and run its timer.
void Client::send_floor(FloorType type) {
  if (!remote_) return;
  const auto packet = floor_message(type);
  floor_timers_.send(packet, monotonic_ms());
  transmit_floor(packet);
}

//------------------------------------------------------------------------------
// TS 24.380 clauses 6.2.4.3.5, 6.2.4.4.5 and 6.2.4.5.3: a Floor Request (Floor Indicator, Location and Floor
// Priority fields), Floor Release (Floor Indicator) or Floor Queue Position Request of this call. Needs remote_.
FloorPacket Client::floor_message(FloorType type) const {
  FloorPacket packet;
  packet.type = type;
  packet.ssrc = remote_->floor_ssrc;
  packet.ack_required = type == FloorType::Release && cfg_.at("mcptt").value("floor_ack", true);

  // TS 24.380 clause 8.2.3.15: the call type of this call, including an emergency
  // private call (MEPP 2) and an emergency or imminent-peril ad hoc call.
  const bool emergency_call = call_type_ == "private" || call_type_ == "ambient" ? mepp_ == 2
                              : call_type_ == "adhoc"                            ? adhoc_priority_ == "emergency"
                                                                                 : emergency_;
  const bool imminent_call = call_type_ == "private" || call_type_ == "ambient" ? false
                             : call_type_ == "adhoc"                            ? adhoc_priority_ == "imminent"
                                                                                : imminent_;
  const bool broadcast_call = call_type_ == "broadcast" || (call_type_ == "adhoc" && adhoc_broadcast_);

  // The Floor Indicator is a field of Floor Request and Floor Release (tables 8.2.4-1, 8.2.7-1), not of Floor Queue
  // Position Request (table 8.2.11-1: SSRC, and Track Info with a non-controlling function only).
  if (type != FloorType::QueuePositionRequest)
    packet.fields = {floor_indicator_field(floor_call_type_bits(broadcast_call, emergency_call, imminent_call))};
  if (type == FloorType::Request) packet.fields.push_back(location_not_provided_field());
  if (type == FloorType::Request)
    packet.fields.push_back(priority_field(static_cast<std::uint8_t>(
        std::min<unsigned>(next_floor_priority_ ? *next_floor_priority_ : cfg_.at("mcptt").value("floor_priority", 0u),
                           remote_->floor_priority.value_or(0)))));
  return packet;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.4.8 and clause 6.2.4.5.3: the user releases the PTT button: stop transmitting and send
// Floor Release when the floor was held or requested.
void Client::release() {
  const bool send_release = (talking_ || requesting_) && !no_floor();
  next_floor_priority_.reset();
  talking_ = requesting_ = false;
  floor_overridden_ = floor_overriding_ = false; // clause 6.2.4.5.3 steps 2 and 3
  live_talk_ = false;
  live_in_.clear();
  transmit_.clear();
  transmit_offset_ = 0;
  if (send_release) {
    if (protected_floor() && !protection_.floor_ready())
      stop_protected_call("floor key expired before release");
    else {
      try {
        send_floor(FloorType::Release);
      } catch (const std::exception &error) {
        failure(error.what());
      }
    }
  }
  floor_event("floor_released");
}

//------------------------------------------------------------------------------
// Helper: write the audio received in the call to the configured WAV file.
void Client::save_audio() {
  if (received_unsaved_ && !received_.empty()) {
    received_unsaved_ = false;
    auto path = cfg_.at("received_wav").get<std::string>();
    write_wav(path + ".tmp", received_);
    std::filesystem::rename(path + ".tmp", path);
  }
}

//------------------------------------------------------------------------------
// Helper: whether a media or floor control packet comes from the server's address and port of the negotiated SDP.
// With media.server_source "port", only the port is checked: the server's packets may reach this client from another
// address when a NAT between them rewrites it (a client on the server's own computer, reached from the server's
// container at its internal address); the default checks both.
bool Client::sent_by_server(const sockaddr_in &source, const std::string &address, std::uint16_t port) const {
  if (cfg_.at("media").value("server_source", std::string("address")) != "port")
    return UdpSocket::matches(source, address, port);
  if (ntohs(source.sin_port) != port) return false;
  char text[INET_ADDRSTRLEN] = {};
  if (inet_ntop(AF_INET, &source.sin_addr, text, sizeof text)) server_sources_[port] = text;
  return true;
}

//------------------------------------------------------------------------------
// Helper: whether a packet comes from the server's media address of the negotiated SDP, from any port.
bool Client::from_server_address(const sockaddr_in &source, const std::string &address) {
  in_addr expected{};
  return inet_pton(AF_INET, address.c_str(), &expected) == 1 && source.sin_addr.s_addr == expected.s_addr;
}

//------------------------------------------------------------------------------
// Helper: the server's address for a port: the SDP address, or with media.server_source "port" the address its
// packets on that port came from.
std::string Client::server_target(const std::string &address, std::uint16_t port) const {
  const auto source = server_sources_.find(port);
  return source == server_sources_.end() ? address : source->second;
}

//------------------------------------------------------------------------------
// Helper: the Floor Indicator value of a message, 0 without one.
static std::uint16_t indicator_of(const FloorPacket &packet) {
  const auto *field = find_floor_field(packet, FloorFieldId::FloorIndicator);
  return field ? read_floor_indicator(*field) : 0;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4: the floor participant receives a floor control message: acknowledge it when asked (Floor
// Ack), then Floor Granted (clause 6.2.4.4.2), Floor Queue Position Info (clause 6.2.4.4.9), Floor Deny
// (clause 6.2.4.4.4), Floor Revoke (clause 6.2.4.5.4), Floor Idle (clause 6.2.4.3.2) or Floor Taken
// (clause 6.2.4.3.3).
void Client::floor_packet(const Datagram &datagram) {
  // TS 24.380 clause 4.1.2.1: the pre-established session's media plane control channel carries its call control
  // messages and, during a call over the session, that call's floor control (mcptt/pre_established.cpp).
  if (pre_session_floor(datagram)) return;
  // TS 24.380 clause 6.2.4.2.2 step 2: before the 200 (OK) of a call offered with an implicit floor request, a
  // floor control message for this client's floor SSRC is stored, and checked in full once the request is
  // accepted (implicit_floor_answered).
  if (implicit_offered_ && !call_answered_) {
    if (wire_ssrc(datagram.bytes, true) == local_.floor_ssrc) early_floor_.push_back(datagram);
    return;
  }
  if (!remote_ || !sent_by_server(datagram.source, remote_->floor_address, remote_->floor_port)) return;
  if (wire_ssrc(datagram.bytes, true) != local_.floor_ssrc) return;
  auto bytes = protected_floor() ? protection_.unprotect_floor(datagram.bytes) : datagram.bytes;
  // TS 24.380 clause 8.1.4: a message of an unknown subtype is ignored whole, unspecified fields and syntactically
  // incorrect fields are ignored.
  auto packet = decode_floor_received(bytes);
  if (!packet) {
    status_["floor_ignored_messages"] = status_.value("floor_ignored_messages", 0u) + 1;
    return;
  }
  if (packet->ssrc != local_.floor_ssrc) return;
  floor_message(*packet, false);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4: a floor control message from the floor control server, received by unicast or (TS 24.380
// clause 10.3.3, mcptt/multicast.cpp) over the MBMS subchannel, whose messages have their own message sequence
// numbers and ask for no Floor Ack.
void Client::floor_message(FloorPacket packet, bool multicast) {
  validate_on_network_floor(packet);
  if (packet.type == FloorType::Ack) {
    floor_timers_.receive(packet, monotonic_ms());
    return;
  }
  last_floor_message_ = client_rules::floor_message_name(static_cast<std::uint8_t>(packet.type));
  if (packet.ack_required) {
    FloorPacket ack;
    ack.type = FloorType::Ack;
    ack.ssrc = remote_->floor_ssrc;
    ack.fields = {source_field(0), message_type_field(packet.type)};
    transmit_floor(ack);
  }
  if (!(multicast ? mbms_floor_sequence_ : floor_sequence_).accept(packet)) return;
  const auto state_before = floor_timers_.state();
  floor_timers_.set_broadcast(broadcast_session());
  floor_timers_.receive(packet, monotonic_ms());
  if (packet.type == FloorType::Granted) {
    if (talking_) return;
    if (floor_timers_.state() != FloorParticipantState::HasPermission && !floor_timers_.awaiting_user_confirmation())
      return;
    audio_ssrc_ = 0;
    for (auto &field : packet.fields)
      if (field.id == static_cast<unsigned>(FloorFieldId::AudioSsrc)) audio_ssrc_ = read_audio_ssrc(field);
    // TS 24.379 clause 6.2.1 NOTE 2 and TS 24.380 clause 14.4: otherwise the "mc_ssrc" of the SDP answer.
    if (!audio_ssrc_) audio_ssrc_ = remote_->granted_audio_ssrc;
    if (!audio_ssrc_) throw std::runtime_error("Floor Granted lacks audio SSRC");
    if (floor_timers_.awaiting_user_confirmation()) {
      floor_event("queued_floor_granted", {{"action", "floor-confirm"}});
      return;
    }
    if (ambient_hidden() && transmit_.empty()) {
      transmit_ = ambient_audio();
      transmit_offset_ = 0;
    }
    talking_ = true;
    requesting_ = false;
    floor_overridden_ = false;
    // TS 24.380 clause 6.2.4.4.2 step 4: the G-bit (Dual floor) stores the overriding without revoke indication.
    floor_overriding_ = (indicator_of(packet) & client_rules::floor_indicator_dual_floor) != 0;
    next_audio_ = monotonic_ms();
    floor_event("floor_granted", {{"overriding", floor_overriding_}});
  } else if (packet.type == FloorType::QueuePositionInfo) {
    status_["floor_queue_info_received"] = status_.value("floor_queue_info_received", 0u) + 1;
    for (const auto &field : packet.fields)
      if (field.id == static_cast<unsigned>(FloorFieldId::QueueInfo))
        status_["queue_position"] = read_queue_info(field).position;
    requesting_ = floor_timers_.state() == FloorParticipantState::Queued;
  } else if (packet.type == FloorType::Deny) {
    if (!requesting_) return;
    talking_ = requesting_ = false;
    transmit_.clear();
    for (const auto &field : packet.fields)
      if (field.id == static_cast<unsigned>(FloorFieldId::RejectCause))
        status_["last_floor_reject_cause"] = read_reject_cause(field).cause; // 5 = receive only
    status_["floor_denials"] = status_["floor_denials"].get<unsigned>() + 1;
    floor_event("floor_denied", {{"reject_cause", status_.value("last_floor_reject_cause", Json())}});
  } else if (packet.type == FloorType::Revoke) {
    release();
    floor_event("floor_revoked");
  } else if (packet.type == FloorType::Idle) {
    // TS 24.380 clause 6.2.4.5.7: in 'U: has permission' a Floor Idle never ends the permission to send media (one
    // over the MBMS subchannel neither: it can be a T7 repetition sent before the grant). The Floor Ack, when asked,
    // was sent above (steps 1a and 2a).
    if (talking_ || state_before == FloorParticipantState::HasPermission) {
      has_permission_floor_idle(packet);
      return;
    }
    status_["floor_owner"] = "";
    receive_ssrc_ = 0;
    // The floor is idle: no talker remains, nor an override (TS 24.380 clauses 6.2.4.3.2, 6.2.4.6.4).
    mix_talkers(true);
    talkers_.clear();
    floor_overridden_ = false;
    status_["multi_talker"] = status_["dual_floor"] = false;
    // TS 24.380 clause 6.2.4.6.4 step 6: in a session initiated as a broadcast group call, "shall indicate to the
    // MCPTT client the media transmission is completed" and enter 'Releasing'.
    if (state_before == FloorParticipantState::PendingRelease &&
        floor_timers_.state() == FloorParticipantState::Releasing)
      floor_event("broadcast_transmission_completed", {{"dialog", dialog_}});
  } else if (packet.type == FloorType::Taken) {
    const auto previous_owner = status_.value("floor_owner", std::string{});
    const auto previous_ssrc = receive_ssrc_;
    receive_ssrc_ = 0;
    for (auto &field : packet.fields) {
      if (field.id == static_cast<unsigned>(FloorFieldId::GrantedPartyIdentity))
        status_["floor_owner"] = read_granted_party_identity(field);
      if (field.id == static_cast<unsigned>(FloorFieldId::AudioSsrc)) receive_ssrc_ = read_audio_ssrc(field);
      if (field.id == static_cast<unsigned>(FloorFieldId::PermissionToRequest))
        status_["permission_to_request"] = read_permission_to_request(field);
      if (field.id == static_cast<unsigned>(FloorFieldId::FloorIndicator))
        status_["floor_indicator"] = read_floor_indicator(field);
      if (field.id == static_cast<unsigned>(FloorFieldId::FunctionalAlias))
        status_["floor_owner_functional_alias"] = read_functional_alias(field);
    }
    floor_taken_talkers(packet, previous_owner, previous_ssrc);
  } else if (packet.type == FloorType::ReleaseMultiTalker) {
    floor_release_multi_talker(packet);
  } else if (packet.type == FloorType::QueuedFloorRequests) {
    queued_floor_requests_received(packet, state_before);
  }
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.5.7 (client_rules::has_permission_floor_idle): a Floor Idle in 'U: has permission'. Step 1:
// the overriding participant removes its overriding without revoke indication (the overridden talker has released);
// step 2: with the G-bit, the overridden participant removes its overridden without revoke indication (the
// overriding talker has released). Either way the other talker's audio ends here, and the participant remains in
// 'U: has permission', sending media. Otherwise clause 6.2.4.1: the message is discarded and nothing changes.
void Client::has_permission_floor_idle(const FloorPacket &packet) {
  const auto outcome =
      client_rules::has_permission_floor_idle(floor_overriding_, floor_overridden_, indicator_of(packet));
  if (outcome == client_rules::HasPermissionIdle::Discard) {
    status_["floor_ignored_messages"] = status_.value("floor_ignored_messages", 0u) + 1;
    return;
  }
  const bool overriding = outcome == client_rules::HasPermissionIdle::EndOverriding;
  (overriding ? floor_overriding_ : floor_overridden_) = false;
  status_["floor_owner"] = "";
  receive_ssrc_ = 0;
  mix_talkers(true);
  talkers_.clear();
  status_["dual_floor"] = false;
  talkers_status();
  floor_event("dual_floor_ended", {{"role", overriding ? "overriding" : "overridden"}});
}

//------------------------------------------------------------------------------
// The session was initiated as a broadcast group call (TS 24.379 clause 6.2.8.2; an ad hoc broadcast call too).
bool Client::broadcast_session() const {
  return call_type_ == "broadcast" || (call_type_ == "adhoc" && adhoc_broadcast_);
}

// TS 24.380 table 8.2.3.15-2: bit G (dual floor) and bit I (multi-talker) of the Floor Indicator.
constexpr std::uint16_t indicator_dual_floor = 0x0200, indicator_multi_talker = 0x0080;

//------------------------------------------------------------------------------
// TS 24.380 clause 8.2.9 and clauses 6.2.4.3.3, 6.2.4.5.8: the talkers a Floor Taken names. With the I-bit
// (multi-talker) its List of Granted Users, List of SSRCs, List of Functional Aliases and List of Locations, "in the
// same order" (they replace the talkers: the server sends the whole list); with the G-bit (dual floor) the overriding
// talker joins the one already talking; otherwise its single granted party. In 'U: has permission' the G-bit tells the
// user the call is overridden without revoke (clause 6.2.4.5.8 step 1) and the I-bit gives the type of call and the
// current talkers (step 2c). A talker's decoder and received audio are kept across the lists.
void Client::floor_taken_talkers(const FloorPacket &packet, const std::string &previous_owner,
                                 std::uint32_t previous_ssrc) {
  const auto indicator = indicator_of(packet);
  const bool multi = (indicator & indicator_multi_talker) != 0, dual = (indicator & indicator_dual_floor) != 0;
  std::vector<std::string> users, aliases;
  std::vector<std::uint32_t> ssrcs;
  std::vector<FloorLocation> locations;
  if (const auto *field = find_floor_field(packet, FloorFieldId::ListOfGrantedUsers)) users = read_uri_list(*field);
  if (const auto *field = find_floor_field(packet, FloorFieldId::ListOfSsrcs)) ssrcs = read_ssrc_list(*field);
  if (const auto *field = find_floor_field(packet, FloorFieldId::ListOfFunctionalAliases))
    aliases = read_uri_list(*field);
  if (const auto *field = find_floor_field(packet, FloorFieldId::ListOfLocations))
    locations = read_location_list(*field);
  const auto location_json = [](const FloorLocation &location) {
    std::string hex;
    for (const auto octet : location.value) {
      static constexpr char digits[] = "0123456789abcdef";
      hex += digits[octet >> 4];
      hex += digits[octet & 15];
    }
    return Json{{"type", location.type}, {"value", hex}};
  };
  auto old = std::exchange(talkers_, {});
  const auto keep = [&](Talker talker) {
    for (auto &previous : old)
      if (previous.ssrc && previous.ssrc == talker.ssrc && previous.codec) {
        talker.codec = std::move(previous.codec);
        talker.frames = std::move(previous.frames);
        talker.packets = previous.packets;
      }
    talkers_.push_back(std::move(talker));
  };
  if (!users.empty() || !ssrcs.empty()) {
    for (std::size_t i = 0; i < std::max(users.size(), ssrcs.size()); ++i) {
      Talker talker;
      if (i < users.size()) talker.user = users[i];
      if (i < ssrcs.size()) talker.ssrc = ssrcs[i];
      if (i < aliases.size()) talker.functional_alias = aliases[i];
      if (i < locations.size()) talker.location = location_json(locations[i]);
      keep(std::move(talker));
    }
  } else {
    Talker talker;
    talker.user = status_.value("floor_owner", std::string{});
    talker.ssrc = receive_ssrc_;
    talker.functional_alias = status_.value("floor_owner_functional_alias", std::string{});
    if (const auto *field = find_floor_field(packet, FloorFieldId::Location))
      talker.location = location_json(read_location(*field));
    if (dual) {
      // The overridden talker goes on (TS 24.380 clause 6.3.6): it stays a talker next to the overriding one.
      for (auto &previous : old)
        if (previous.ssrc != talker.ssrc) {
          Talker kept;
          kept.user = previous.user;
          kept.ssrc = previous.ssrc;
          kept.functional_alias = previous.functional_alias;
          kept.location = previous.location;
          keep(std::move(kept));
        }
      if (talkers_.empty() && previous_ssrc && previous_ssrc != talker.ssrc) {
        Talker kept;
        kept.user = previous_owner;
        kept.ssrc = previous_ssrc;
        keep(std::move(kept));
      }
    }
    keep(std::move(talker));
  }
  status_["multi_talker"] = multi;
  status_["dual_floor"] = dual;
  // With several talkers each one's stream is followed by its SSRC (audio_packet), not by receive_ssrc_.
  if (talkers_.size() > 1) receive_ssrc_ = 0;
  if (talkers_.size() == 1 && !receive_ssrc_ && talkers_.front().ssrc != audio_ssrc_)
    receive_ssrc_ = talkers_.front().ssrc;
  if (dual && talking_ && !floor_overridden_) {
    floor_overridden_ = true;
    floor_event("floor_overridden", {{"by", status_.value("floor_owner", std::string{})}});
  }
  talkers_status();
  if (multi || dual) {
    Json names = Json::array();
    for (const auto &talker : talkers_) names.push_back(talker.user);
    floor_event(multi ? "multi_talker_floor_taken" : "dual_floor_taken", {{"talkers", names}});
  }
}

//------------------------------------------------------------------------------
// TS 24.380 clauses 6.2.4.3.7, 6.2.4.4.10, 6.2.4.5.9, 6.2.4.6.9 and 6.2.4.9.14: Floor Release Multi Talker: "a
// notification to the user indicating that a participant has released the floor in a multi-talker group"; the talker
// (its User ID, its SSRC in the Audio SSRC field) leaves the talkers, after the audio it sent has been mixed.
void Client::floor_release_multi_talker(const FloorPacket &packet) {
  const auto *user_field = find_floor_field(packet, FloorFieldId::UserId);
  const auto user_id = user_field ? read_user_id(*user_field) : std::string{};
  const auto *ssrc_field = find_floor_field(packet, FloorFieldId::AudioSsrc);
  const auto ssrc = ssrc_field ? read_audio_ssrc(*ssrc_field) : 0;
  mix_talkers(true);
  std::erase_if(talkers_, [&](const Talker &talker) {
    return (!user_id.empty() && talker.user == user_id) || (ssrc && talker.ssrc == ssrc);
  });
  if (talkers_.size() == 1) receive_ssrc_ = talkers_.front().ssrc;
  if (talkers_.empty()) receive_ssrc_ = 0;
  const Json released{{"user", user_id}, {"ssrc", ssrc}, {"own", user_id == user()}};
  status_["last_multi_talker_release"] = released;
  talkers_status();
  floor_event("floor_release_multi_talker", released);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.7.6 (Cancel Result: "may provide the result ... to the MCPTT user", T134 stopped) and
// clause 6.2.4.9.15 (Cancel Notification in 'U: queued': "shall provide a queued floor requests cancellation
// notification to the MCPTT user", "may display the requesting user" from the Requested Party's Identity, T104
// stopped, 'U: has no permission'). The Floor Ack, when asked, was sent by floor_message. Status "queued_requests".
void Client::queued_floor_requests_received(const FloorPacket &packet, FloorParticipantState state_before) {
  const auto purpose =
      read_queued_requests_purpose(*find_floor_field(packet, FloorFieldId::QueuedFloorRequestsPurpose));
  auto &queued = status_["queued_requests"];
  if (!queued.is_object()) queued = Json::object();
  Json users = Json::array();
  if (const auto *field = find_floor_field(packet, FloorFieldId::ListOfQueuedUsers))
    for (const auto &queued_user : read_uri_list(*field)) users.push_back(queued_user);
  if (purpose == static_cast<std::uint16_t>(QueuedRequestsPurpose::CancelResult)) {
    const auto *result = find_floor_field(packet, FloorFieldId::QueuedFloorRequestsResult);
    queued["state"] = "result";
    queued["result"] = result ? Json(read_queued_requests_result(*result)) : Json();
    queued["not_removed"] = users; // clause 8.2.15: the users whose queued requests remain
    queued["results"] = queued.value("results", 0u) + 1;
    floor_event("queued_requests_result", {{"result", queued["result"]}, {"not_removed", users}});
  } else if (purpose == static_cast<std::uint16_t>(QueuedRequestsPurpose::CancelNotification)) {
    const auto *by = find_floor_field(packet, FloorFieldId::UserId);
    const bool was_queued = state_before == FloorParticipantState::Queued;
    if (!was_queued) return; // clause 6.2.4.9.15 is the procedure of 'U: queued' only
    queued["state"] = "cancelled";
    queued["by"] = by ? read_user_id(*by) : std::string{};
    queued["notifications"] = queued.value("notifications", 0u) + 1;
    requesting_ = false;
    transmit_.clear();
    status_.erase("queue_position");
    floor_event("queued_requests_cancelled", {{"by", queued["by"]}});
  }
}

//------------------------------------------------------------------------------
// Status: "talkers", the MCPTT IDs of the granted users of a multi-talker or dual floor call (each change also as a
// "talkers" event), and "talker_details" with each one's SSRC, functional alias, location and received packets.
void Client::talkers_status() {
  Json names = Json::array(), details = Json::array();
  for (const auto &talker : talkers_) {
    names.push_back(talker.user);
    Json detail{{"user", talker.user}, {"ssrc", talker.ssrc}, {"packets", talker.packets}};
    if (!talker.functional_alias.empty()) detail["functional_alias"] = talker.functional_alias;
    if (!talker.location.is_null()) detail["location"] = talker.location;
    details.push_back(detail);
  }
  const bool changed = status_.value("talkers", Json::array()) != names;
  status_["talkers"] = names;
  if (changed) event("talkers", {{"talkers", names}});
  status_["talker_details"] = details;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.7.4: the user cancels the queued floor requests of the users given (List of Queued Users),
// or with none all of them: Queued Floor Requests with the purpose Cancel Request, this user's Functional Alias
// (clause 8.2.15: included for a participant's cancellation) and identity (Requested Party's Identity, coded as the
// User ID), T134 started, the state kept. On a group configured for audio cut-in floor control the user is told it is
// not allowed, and nothing is sent.
void Client::queued_requests_command(const std::string &arguments) {
  if (!remote_ || no_floor()) throw std::runtime_error("queued-requests: no call with floor control");
  if (const auto *settings = call_group_floor_settings(); settings && settings->audio_cut_in) {
    auto &queued = status_["queued_requests"];
    if (!queued.is_object()) queued = Json::object();
    queued["state"] = "not allowed";
    floor_event("queued_requests_not_allowed", {{"reason", "audio cut-in floor control"}});
    return;
  }
  std::vector<std::string> users;
  std::istringstream words(arguments);
  for (std::string word; words >> word;) users.push_back(word);
  FloorPacket packet;
  packet.type = FloorType::QueuedFloorRequests;
  packet.ssrc = remote_->floor_ssrc;
  packet.fields = {queued_requests_purpose_field(QueuedRequestsPurpose::CancelRequest)};
  if (!users.empty()) packet.fields.push_back(uri_list_field(FloorFieldId::ListOfQueuedUsers, users));
  if (!functional_alias_.empty()) packet.fields.push_back(functional_alias_field(functional_alias_));
  packet.fields.push_back(user_id_field(user()));
  floor_timers_.send(packet, monotonic_ms());
  transmit_floor(packet);
  auto &queued = status_["queued_requests"];
  if (!queued.is_object()) queued = Json::object();
  queued["state"] = "requested";
  queued["users"] = users;
  queued["requests"] = queued.value("requests", 0u) + 1;
  event("queued_requests_sent", {{"users", users}});
}

//------------------------------------------------------------------------------
// TS 24.380 clauses 6.2.4.3.8 and 6.2.4.3.9: in 'U: has no permission', the user asks the server to stop (Media Flow
// Control Indicator bit A '0') or resume ('1') the unicast media: Unicast Media Flow Control with this user's User ID
// (clause 8.2.16). Status "media_flow": "stopped" or "flowing".
void Client::media_flow_command(bool resume) {
  if (!remote_ || no_floor()) throw std::runtime_error("media-stop/media-resume: no call with floor control");
  FloorPacket packet;
  packet.type = FloorType::UnicastMediaFlowControl;
  packet.ssrc = remote_->floor_ssrc;
  packet.fields = {user_id_field(user()), media_flow_control_field(resume)};
  floor_timers_.send(packet, monotonic_ms()); // refused outside 'U: has no permission'
  transmit_floor(packet);
  media_flow_stopped_ = !resume;
  status_["media_flow"] = resume ? "flowing" : "stopped";
  event("media_flow", {{"media_flow", status_["media_flow"]}});
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.3.10: in 'U: has no permission', the user asks to revoke the floor of a user: Floor Revoke
// Request with the Floor Revoke Request User Identity, a Floor Priority when not the normal one (mcptt.floor_priority,
// not above the negotiated one, clause 14.3.3) and, when known, the talker's Audio SSRC (clause 8.2.17).
void Client::revoke_request_command(const std::string &target) {
  if (!remote_ || no_floor()) throw std::runtime_error("revoke-request: no call with floor control");
  if (target.empty() || target.find(' ') != std::string::npos)
    throw std::runtime_error("usage: revoke-request <MCPTT ID>");
  FloorPacket packet;
  packet.type = FloorType::RevokeRequest;
  packet.ssrc = remote_->floor_ssrc;
  const auto priority = std::min<unsigned>(
      next_floor_priority_ ? *next_floor_priority_ : cfg_.at("mcptt").value("floor_priority", 0u),
      remote_->floor_priority.value_or(0));
  if (priority) packet.fields.push_back(priority_field(static_cast<std::uint8_t>(priority)));
  packet.fields.push_back(revoke_request_user_field(target));
  std::uint32_t ssrc = 0;
  for (const auto &talker : talkers_)
    if (talker.user == target) ssrc = talker.ssrc;
  if (!ssrc && status_.value("floor_owner", std::string{}) == target) ssrc = receive_ssrc_;
  if (ssrc) packet.fields.push_back(audio_ssrc_field(ssrc));
  floor_timers_.send(packet, monotonic_ms()); // refused outside 'U: has no permission'
  transmit_floor(packet);
  event("floor_revoke_requested", {{"user", target}, {"ssrc", ssrc}});
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.3.4: receive RTP media from the floor owner (or, without floor control, TS 24.379
// clause 11.1.2, from the first sender) and keep the decoded audio, or with media.live pass it on. Speech received
// on a joined host group (mcptt/multicast.cpp) comes from the server's media address, from a port of the server's
// own choosing; the talker drops its own speech sent back to it there.
void Client::audio_packet(const Datagram &datagram, bool multicast) {
  if (!remote_ || status_.value("call_state", "") != "active") return;
  if (multicast ? talking_ || !from_server_address(datagram.source, remote_->address)
                : !sent_by_server(datagram.source, remote_->address, remote_->audio_port))
    return;
  // Without floor control no Floor Taken names the sender's SSRC: the first one
  // heard is followed (TS 24.379 clause 11.1.2).
  if (no_floor() && !receive_ssrc_) receive_ssrc_ = wire_ssrc(datagram.bytes, false);
  // TS 24.380 clauses 6.2.4.5.8 and 8.2.9: in a multi-talker or dual floor call each granted talker's stream (its SSRC
  // of the List of SSRCs, or of the Floor Taken of each talker) is received and mixed with the others.
  const auto ssrc = wire_ssrc(datagram.bytes, false);
  Talker *talker = nullptr;
  if (talkers_.size() > 1)
    for (auto &candidate : talkers_)
      if (candidate.ssrc && candidate.ssrc == ssrc && candidate.ssrc != audio_ssrc_) talker = &candidate;
  if (!talker && (!receive_ssrc_ || ssrc != receive_ssrc_)) return;
  if (duplicate_rtp(datagram.bytes, multicast) || hold_rtp(datagram, multicast)) return;
  const auto sender = talker      ? talker->user
                      : no_floor() ? private_peer_
                                   : status_.value("floor_owner", std::string{});
  auto bytes = protected_media() ? protection_.unprotect_media(datagram.bytes, sender) : datagram.bytes;
  auto rtp = decode_rtp(bytes);
  if (rtp.payload_type != local_.payload_type || rtp.ssrc != ssrc) return;
  kept_rtp(datagram.bytes);
  floor_timers_.media_received(monotonic_ms());
  // TS 24.380 clauses 6.2.4.3.4, 6.2.4.4.7 ...: "request the MCPTT client to start rendering the received RTP media".
  if (!status_.value("media_rendering", false)) {
    status_["media_rendering"] = true;
    event("media_rendering", {{"on", true}});
  }
  if (talker) {
    if (!talker->codec) talker->codec = std::make_unique<AmrWbCodec>();
    auto pcm = talker->codec->decode(unpack_amr_wb(rtp.payload, local_.octet_aligned));
    capture_speech(pcm, ssrc, sender, monotonic_ms());
    ++talker->packets;
    talker->frames.push_back(std::move(pcm));
    mix_talkers(false);
    status_["received_packets"] = status_["received_packets"].get<unsigned>() + 1;
    return;
  }
  auto pcm = codec_.decode(unpack_amr_wb(rtp.payload, local_.octet_aligned));
  capture_speech(pcm, receive_ssrc_, sender, monotonic_ms()); // media.speech_dir: for an agent
  deliver_received_audio(pcm);
  status_["received_packets"] = status_["received_packets"].get<unsigned>() + 1;
  if (multicast) status_["multicast_received_packets"] = status_.value("multicast_received_packets", 0ULL) + 1;
  if (!held_rtp_.empty()) release_held_rtp(monotonic_ms());
}

//------------------------------------------------------------------------------
// Received audio: with media.live to the dispatcher's service, else kept for received_wav.
void Client::deliver_received_audio(const std::vector<std::int16_t> &pcm) {
  if (live_) {
    forward_live_audio(pcm); // live (media.live): to the dispatcher's service, not kept
  } else {
    // media.keep_received_audio (default true, for received_wav): kept at most media.received_audio_max_s seconds
    // (default 600), the oldest dropped first; off, nothing is kept (load clients).
    const auto &media = cfg_.at("media");
    if (media.value("keep_received_audio", true)) {
      constexpr std::size_t rate = 16000;
      received_.insert(received_.end(), pcm.begin(), pcm.end());
      const auto limit = rate * media.value("received_audio_max_s", 600u);
      if (const auto drop = client_rules::audio_overflow(received_.size(), limit, rate))
        received_.erase(received_.begin(), received_.begin() + static_cast<std::ptrdiff_t>(drop));
      received_unsaved_ = true;
    }
  }
}

} // namespace mcx
