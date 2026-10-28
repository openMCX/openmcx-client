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
  if ((media.control == MediaControl::none) != no_floor())
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
// TS 24.380 clause 14.4: "if the attribute contains attribute parameters that were not present in the associated
// offer, the offerer shall discard those attribute parameters": "mc_queueing" without this client's, and
// "mc_implicit_request" unless this client's INVITE requested the floor with it (implicit_offered_). The "mc_ssrc"
// is kept: the offerer "shall use the returned value [...] even if the "mc_ssrc" attribute was not present in the
// associated offer". For a server's offer this is the negotiated outcome as well (clause 14.3.2).
MediaDescription Client::negotiated_media(MediaDescription media) const {
  media.queueing = client_rules::answer_parameter_kept(media.queueing, local_.queueing);
  media.implicit_request = client_rules::answer_parameter_kept(media.implicit_request, implicit_offered_);
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
  try {
    invite();
  } catch (...) {
    local_.implicit_request = implicit_offered_ = false;
    throw;
  }
  local_.implicit_request = false;
  implicit_offered_ = implicit && own;
  // The outcome of the last implicit floor request: "offered", then "accepted" or "not accepted".
  if (implicit_offered_) status_["implicit_floor_request"] = "offered";
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
  if (!ipcon_dialog_.empty() && id == ipcon_dialog_) {
    if (state == SipCallState::disconnected) {
      if (ipcon_result_.is_object() && ipcon_result_.value("state", "") == "open") ipcon_result_["state"] = "released";
      ipcon_reset();
      event("mcdata_ipconn_released", {{"dialog", std::string(id)}, {"status", code}});
    }
    return;
  }
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
    if (cfg_.at("mcptt").value("conference_notifications", true)) schedule([this] { subscribe_conference(); });
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
  transmit_.clear();
  multicast_leave(false);

  // A private session cannot be rejoined; keep only group session identities.
  if (call_type_ == "private" || call_type_ == "ambient") {
    session_uri_.clear();
    status_["session_uri"] = "";
  }
  local_.direction = "sendrecv";
  local_.implicit_request = false;
  implicit_offered_ = call_answered_ = false;
  early_floor_.clear();
  local_.control = MediaControl::floor;
  leave_monitored_call();
  last_call_type_ = call_type_;
  last_call_group_ = call_group_;
  call_type_.clear();
  if (!call_group_.empty()) {
    call_group_.clear();
    restore_group_key();
  }
  private_peer_.clear();

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
  for (const auto *key : {"permission_to_request", "floor_indicator", "last_floor_reject_cause"}) status_.erase(key);
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
        std::min<unsigned>(cfg_.at("mcptt").value("floor_priority", 0u), remote_->floor_priority.value_or(0)))));
  return packet;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.4.8 and clause 6.2.4.5.3: the user releases the PTT button: stop transmitting and send
// Floor Release when the floor was held or requested.
void Client::release() {
  const bool send_release = (talking_ || requesting_) && !no_floor();
  talking_ = requesting_ = false;
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
  event("floor_released");
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
// TS 24.380 clause 6.2.4: the floor participant receives a floor control message: acknowledge it when asked (Floor
// Ack), then Floor Granted (clause 6.2.4.4.2), Floor Queue Position Info (clause 6.2.4.4.9), Floor Deny
// (clause 6.2.4.4.4), Floor Revoke (clause 6.2.4.5.4), Floor Idle (clause 6.2.4.3.2) or Floor Taken
// (clause 6.2.4.3.3).
void Client::floor_packet(const Datagram &datagram) {
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
  auto packet = decode_floor(bytes);
  if (packet.ssrc != local_.floor_ssrc) return;
  floor_message(packet, false);
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
      event("queued_floor_granted", {{"action", "floor-confirm"}});
      return;
    }
    if (ambient_hidden() && transmit_.empty()) {
      transmit_ = ambient_audio();
      transmit_offset_ = 0;
    }
    talking_ = true;
    requesting_ = false;
    next_audio_ = monotonic_ms();
    event("floor_granted");
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
    event("floor_denied", {{"reject_cause", status_.value("last_floor_reject_cause", Json())}});
  } else if (packet.type == FloorType::Revoke) {
    release();
    event("floor_revoked");
  } else if (packet.type == FloorType::Idle) {
    // TS 24.380 clause 6.2.4.4: 'U: has permission' takes no Floor Idle. One over the subchannel can be a T7
    // repetition sent before the server granted this user's request, overtaken by the unicast Floor Granted.
    if (talking_ && !multicast) release();
    status_["floor_owner"] = "";
    receive_ssrc_ = 0;
  } else if (packet.type == FloorType::Taken) {
    receive_ssrc_ = 0;
    for (auto &field : packet.fields) {
      if (field.id == static_cast<unsigned>(FloorFieldId::GrantedPartyIdentity))
        status_["floor_owner"] = read_granted_party_identity(field);
      if (field.id == static_cast<unsigned>(FloorFieldId::AudioSsrc)) receive_ssrc_ = read_audio_ssrc(field);
      if (field.id == static_cast<unsigned>(FloorFieldId::PermissionToRequest))
        status_["permission_to_request"] = read_permission_to_request(field);
      if (field.id == static_cast<unsigned>(FloorFieldId::FloorIndicator))
        status_["floor_indicator"] = read_floor_indicator(field);
    }
  }
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
  if (!receive_ssrc_ || wire_ssrc(datagram.bytes, false) != receive_ssrc_) return;
  if (duplicate_rtp(datagram.bytes, multicast) || hold_rtp(datagram, multicast)) return;
  const auto sender = no_floor() ? private_peer_ : status_.value("floor_owner", std::string{});
  auto bytes = protected_media() ? protection_.unprotect_media(datagram.bytes, sender) : datagram.bytes;
  auto rtp = decode_rtp(bytes);
  if (rtp.payload_type != local_.payload_type || !receive_ssrc_ || rtp.ssrc != receive_ssrc_) return;
  kept_rtp(datagram.bytes);
  floor_timers_.media_received(monotonic_ms());
  auto pcm = codec_.decode(unpack_amr_wb(rtp.payload, local_.octet_aligned));
  capture_speech(pcm, receive_ssrc_, sender, monotonic_ms()); // media.speech_dir: for an agent
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
  status_["received_packets"] = status_["received_packets"].get<unsigned>() + 1;
  if (multicast) status_["multicast_received_packets"] = status_.value("multicast_received_packets", 0ULL) + 1;
  if (!held_rtp_.empty()) release_held_rtp(monotonic_ms());
}

} // namespace mcx
