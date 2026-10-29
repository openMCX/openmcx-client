//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The MC client: construction, start-up (profiles, group document, keys, SIP registration), the run loop, the
// periodic tick (timers, speech transmission, status) and the MCVideo user profile.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19),
// TS 24.282 V19.7.0 (Rel-19), TS 24.481 V19.3.0 (Rel-19), TS 24.482 V19.1.0 (Rel-19), TS 24.484 V19.6.0 (Rel-19),
// TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

#include <unistd.h>

namespace mcx {

//------------------------------------------------------------------------------
// Helper: stop the message store poll thread; it ends with its next poll and the process does not wait for it.
Client::~Client() {
  if (live_video_fd_ >= 0) ::close(live_video_fd_);
  if (store_channel_.running) *store_channel_.running = false;
  if (store_channel_.poller.joinable()) store_channel_.poller.detach();
}

//------------------------------------------------------------------------------
// Helper: the periodic work of the run loop: location, MCData and MCVideo ticks, key refresh, key and authorisation
// expiry, the floor participant's timers (TS 24.380 clause 11.1.1), speech transmission in 20 ms AMR-WB frames and
// the status file.
void Client::tick() {
  auto now = monotonic_ms();
  location_tick(now);
  mcdata_location_tick(now);
  mcvideo_location_tick(now);
  seal_tick(now);
  store_tick();
  if (mcdata_enabled()) mcdata_tick();
  if (mcvideo_enabled()) mcvideo_tick(now);
  // RFC 6665 section 4.1.2.2: the GKTP subscriptions are refreshed in their dialogs before they expire.
  if (keys_ && key_refresh_at_ && std::time(nullptr) >= key_refresh_at_) refresh_key_subscription(false);
  if (keys_)
    for (const auto &[dialog, at] : std::map(extra_key_refresh_at_))
      if (at && std::time(nullptr) >= at) refresh_extra_key_subscription(dialog);
  if (mcvideo_keys_ && mcvideo_key_refresh_at_ && std::time(nullptr) >= mcvideo_key_refresh_at_)
    refresh_key_subscription(true);
  configuration_subscription_tick();
  status_["protected_media"] = protected_media();
  status_["protected_floor"] = protected_floor();
  status_["protected_ready"] =
      (!protected_media() || media_key_ready()) && (!protected_floor() || protection_.floor_ready());
  if (remote_ && !status_["protected_ready"].get<bool>()) {
    stop_protected_call("protected call key expired or unavailable");
    return;
  }
  // RFC 6665 section 4.1.2.1: the affiliation status subscriptions are refreshed before the granted duration ends.
  if (subscription_refresh_at_ && std::time(nullptr) >= subscription_refresh_at_)
    refresh_affiliation_subscription(false);
  if (mcvideo_subscription_refresh_at_ && std::time(nullptr) >= mcvideo_subscription_refresh_at_)
    refresh_affiliation_subscription(true);
  if (subscription_expires_ && std::time(nullptr) >= subscription_expires_) {
    subscription_expires_ = 0;
    affiliated_ = false;
    release();
    if (!dialog_.empty()) sip_.bye(dialog_);
    schedule([this] { authorize(); });
  }
  if (authorized_ && std::time(nullptr) >= expires_) {
    authorized_ = affiliated_ = false;
    release();
    if (!dialog_.empty()) sip_.bye(dialog_);
    failure("service authorization expired");
  }
  // TS 33.180 annex B.12: the expired access token is no longer considered valid, and no request carries it again
  // (access_token()); the SIP bodies built from now on have none. The refresh token stays for a renewal.
  if (!token_.empty() && std::time(nullptr) > expires_) {
    token_.clear();
    event("access_token_expired", {{"expires_at", expires_}});
  }
  if (remote_ && !no_floor()) floor_participant_tick(now);
  speech_capture_tick(now);
  if (!held_rtp_.empty()) release_held_rtp(now);
  if (talking_ && remote_ && now >= next_audio_) {
    if (live_talk_)
      live_speech_tick(now);
    else
      speech_tick(now);
  }
  if (now - last_save_ >= 200) save_status(now);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 11.1.1 and clause 6.2.4: the floor participant's timers, their retransmissions and failures, the
// end of received media, and the end of this user's transmission once the floor is lost.
void Client::floor_participant_tick(std::uint64_t now) {
  const bool receiving = floor_timers_.remote_media_active();
  for (const auto &packet : floor_timers_.tick(now)) transmit_floor(packet);
  for (const auto type : floor_timers_.take_failed())
    failure("floor procedure timeout for type " + std::to_string(static_cast<unsigned>(type)));
  if (receiving && !floor_timers_.remote_media_active()) {
    receive_ssrc_ = 0;
    status_["floor_owner"] = "";
  }
  const auto state = floor_timers_.state();
  if (state == FloorParticipantState::NoPermission || state == FloorParticipantState::PendingRelease) {
    // TS 24.380 clause 6.2.4: the floor ended without this user releasing it. Reported, so that no talk burst
    // ends silently.
    if (talking_) {
      const Json loss{{"state", state == FloorParticipantState::NoPermission ? "NoPermission" : "PendingRelease"},
                      {"last_message", last_floor_message_}};
      status_["last_floor_loss"] = loss;
      event("floor_lost", loss);
    }
    talking_ = requesting_ = false;
    transmit_.clear();
  }
}

//------------------------------------------------------------------------------
// Helper: the next 20 ms AMR-WB frame of this user's speech (SRTP-protected in a protected call, TS 33.180
// clause 7.5), or the end of the transmission when the file is exhausted.
void Client::speech_tick(std::uint64_t now) {
  if (transmit_offset_ >= transmit_.size()) {
    if (ambient_hidden() && !transmit_.empty())
      transmit_offset_ = 0; // keeps transmitting
    else
      release();
  } else {
    std::array<std::int16_t, 320> frame{};
    auto count = std::min<size_t>(320, transmit_.size() - transmit_offset_);
    std::copy_n(transmit_.begin() + transmit_offset_, count, frame.begin());
    transmit_offset_ += count;
    send_speech_frame(frame, transmit_offset_ <= 320, now);
  }
}

//------------------------------------------------------------------------------
// Helper: one 20 ms frame of this user's speech as AMR-WB (RFC 4867) in RTP to the call's media address,
// SRTP-protected in a protected call (TS 33.180 clause 7.5); the next frame is due 20 ms later.
void Client::send_speech_frame(const std::array<std::int16_t, 320> &frame, bool marker, std::uint64_t now) {
  RtpPacket rtp;
  rtp.payload_type = remote_->payload_type;
  rtp.sequence = sequence_++;
  rtp.timestamp = timestamp_;
  timestamp_ += 320;
  rtp.ssrc = audio_ssrc_;
  rtp.marker = marker;
  // TS 26.179 clause 4.1.3: the configured mode, within the peer's mode-set (RFC 4867 section 8.1).
  const auto mode = amr_wb_send_mode(cfg_.at("media").value("mode", 0), remote_->mode_set);
  rtp.payload = pack_amr_wb(codec_.encode(frame, mode), remote_->octet_aligned);
  auto bytes = encode_rtp(rtp);
  bool send = true;
  if (protected_media()) try {
      bytes = protection_.protect_media(bytes, user());
    } catch (const PacketKeyExpired &) {
      // TS 33.180 annex E.6.10: past the PCK's Expiry time the frame is not sent (never in the clear); the call
      // itself is left as it is.
      send = false;
      status_["media_key_expired_packets"] = status_.value("media_key_expired_packets", 0ULL) + 1;
    }
  if (send) {
    audio_.send(bytes, server_target(remote_->address, remote_->audio_port), remote_->audio_port);
    status_["sent_packets"] = status_["sent_packets"].get<unsigned>() + 1;
  }
  next_audio_ += 20;
  if (next_audio_ + 200 < now) next_audio_ = now + 20;
}

//------------------------------------------------------------------------------
// Helper: the status file (every 200 ms): the floor state, priorities, call, services, registration and
// authorisation, then the MCData and MCVideo parts; the received audio is saved with it.
void Client::save_status(std::uint64_t now) {
  floor_and_priority_status();
  // A listened-to client shows no call (TS 24.379 clause 11.1.6.2.0); the "ambient" entry is
  // test instrumentation, not a user indication.
  const bool hidden = ambient_hidden();
  status_["call_type"] = hidden ? "" : call_type_;
  status_["call_group"] = call_group_;
  status_["private_peer"] = hidden ? "" : private_peer_;
  status_["ambient"] = !ambient_ ? Json(nullptr)
                                 : Json{{"type", ambient_->type},
                                        {"role", ambient_->role},
                                        {"peer", ambient_->peer},
                                        {"release_allowed", ambient_->release_allowed},
                                        {"null_alert", ambient_->null_alert},
                                        {"attached", !dialog_.empty() && dialog_ == ambient_->dialog},
                                        {"transmitting", hidden && talking_},
                                        {"connected", hidden && remote_.has_value()}};
  status_["last_ambient"] = last_ambient_;
  status_["first_to_answer"] = first_to_answer_;
  status_["private_emergency_state"] = mepc_name(mepc_);
  status_["private_priority_state"] = mepp_name(mepp_);
  status_["private_alert_state"] = mpea_name(mpea_);
  status_["private_alerts"] = private_alerts_;
  status_["adhoc_priority"] = adhoc_priority_;
  status_["functional_alias"] = functional_alias_;
  status_["adhoc_alert_state"] = ah_mea_ == 4   ? "AH-MEA 4: adhoc-emergency-alert-cancel-pending"
                                 : ah_mea_ == 3 ? "AH-MEA 3: adhoc-emergency-alert-initiated"
                                 : ah_mea_ == 2 ? "AH-MEA 2: adhoc-emergency-alert-confirm-pending"
                                                : "AH-MEA 1: no-adhoc-alert";
  status_["adhoc_alert_group"] = own_alert_group_;
  status_["received_adhoc_alerts"] = received_adhoc_alerts_;
  status_["callback_requests_sent"] = pccb_requesting_;
  status_["callback_requests_received"] = pccb_target_;
  status_["service_outcomes"] = service_outcomes_;
  status_["profile_actions"] = profile_actions_;
  status_["enforce_profile_ruleset"] = cfg_.at("mcptt").value("enforce_profile_ruleset", true);
  status_["requested_functional_aliases"] = requested_aliases_;
  status_["active_functional_aliases"] = active_aliases_;
  status_["takeover_possible_functional_aliases"] = takeover_possible_aliases_;
  status_["audio_ssrc"] = audio_ssrc_;
  // The call's floor control SSRCs (TS 24.380 clause 4.3.3.1): this client's, for the server's messages, and the
  // server's, for this client's; 0 when none is negotiated.
  status_["floor_ssrc"] = local_.floor_ssrc;
  status_["remote_floor_ssrc"] = remote_ ? remote_->floor_ssrc : 0;
  // The MCPTT call's dialog, and the MCVideo dialogs ended by this client whose end the SIP stack has not reported.
  status_["call_dialog"] = dialog_;
  status_["mcvideo_closing_dialogs"] = mcvideo_closing_.size();
  status_["floor_control"] = !no_floor();
  status_["pending_floor_confirmation"] = floor_timers_.awaiting_user_confirmation();
  status_["queued"] = floor_timers_.state() == FloorParticipantState::Queued;
  status_["registered"] = registered_;
  status_["sip_security"] = sip_security_status();
  status_["authorized"] = authorized_;
  status_["signed_in"] = signed_in_;
  status_["service_state"] = service_state();
  status_["sign_in_failures"] = sign_in_failures_;
  status_["token_expires_at"] = expires_;
  status_["token_renewals"] = token_renewals_;
  status_["token_refreshes"] = token_refreshes_;
  status_["token_refresh_refusals"] = refresh_refusals_;
  status_["password_logins"] = password_logins_;
  status_["browser_logins"] = browser_logins_;
  status_["location"] = location_status();
  status_["group_documents"] = group_documents_;
  if (mcdata_enabled()) status_["mcdata_location"] = mcdata_location_status();
  if (mcdata_enabled()) status_["msgstore"] = store_status_;
  status_["affiliated"] = affiliated_;
  status_["desired_affiliation"] = desired_affiliation_;
  status_["affiliations"] = affiliation_statuses_;
  status_["monitored_groups"] = monitored_groups_;
  status_["monitored"] = monitored_state_;
  status_["media_ready"] = remote_.has_value();
  status_["conference_subscription"] = conference_subscription_;
  status_["talking"] = talking_ && !hidden;
  status_["requesting_floor"] = requesting_;
  status_["received_samples"] = received_.size();
  if (mcdata_enabled()) mcdata_status();
  if (mcvideo_enabled()) mcvideo_status();
  write_status(cfg_, status_);
  save_audio();
  last_save_ = now;
}

//------------------------------------------------------------------------------
// Helper: the floor participant state (TS 24.380 clause 6.2.4) and the priority state of the status file.
void Client::floor_and_priority_status() {
  switch (floor_timers_.state()) {
  case FloorParticipantState::NoPermission: status_["floor_state"] = "NoPermission"; break;
  case FloorParticipantState::PendingRequest: status_["floor_state"] = "PendingRequest"; break;
  case FloorParticipantState::HasPermission: status_["floor_state"] = "HasPermission"; break;
  case FloorParticipantState::PendingRelease: status_["floor_state"] = "PendingRelease"; break;
  case FloorParticipantState::Queued: status_["floor_state"] = "Queued"; break;
  }
  status_["priority"] = emergency_ ? "emergency" : imminent_ ? "imminent" : "normal";
  status_["alerts"] = alerts_;
  // The group whose emergency alerts "alerts" lists: during a call on a monitored group that call's, else the
  // selected group's (a cancellation names it, TS 24.379 clause 12.1.1.2 step 4a).
  status_["alerts_group"] = selected_priority_.has_value() ? call_group_ : group();
  status_["alert_pending"] = alert_pending_;
  status_["priority_pending"] = priority_pending_;
  status_["negotiated_floor_priority"] =
      remote_ && remote_->floor_priority ? Json(*remote_->floor_priority) : Json(nullptr);
  status_["answer_mode"] = cfg_.at("mcptt").value("answer_mode", "auto");
  status_["group_id"] = group();
}

//------------------------------------------------------------------------------
// Helper: build the client from its configuration: the SIP engine with its callbacks (incoming requests, call
// states, negotiated media checked per TS 24.379 clause 6.2.1), the media sockets and the initial status.
Client::Client(Json cfg, bool disable_tls)
    : cfg_(std::move(cfg)),
      // http.connect_timeout_ms and http.timeout_ms (defaults 2 s and 15 s): a burst of activations and sign-ins
      // takes IdMS longer than the library's 5 s.
      http_(cfg_.at("tls").at("ca_file"),
            HttpTimeouts{cfg_.value("http", Json::object()).value("connect_timeout_ms", 2000L),
                         cfg_.value("http", Json::object()).value("timeout_ms", 15000L)},
            address_map(cfg_), HttpTrust::configured, crl_file(cfg_)),
      idms_http_(cfg_.at("tls").at("ca_file"),
                 HttpTimeouts{cfg_.value("http", Json::object()).value("connect_timeout_ms", 2000L),
                              cfg_.value("http", Json::object()).value("timeout_ms", 15000L)},
                 address_map(cfg_), HttpTrust::configured_and_system, crl_file(cfg_)),
      kms_http_(cfg_.at("tls").at("ca_file"),
                HttpTimeouts{cfg_.value("http", Json::object()).value("connect_timeout_ms", 2000L),
                             cfg_.value("http", Json::object()).value("timeout_ms", 15000L)},
                address_map(cfg_), HttpTrust::configured, crl_file(cfg_)),
      control_(cfg_.at("control_fifo")),
      sip_(client_sip_config(cfg_, disable_tls),
           {[this](const SipMessage &message) { return incoming(message); },
            [this](std::string_view id, SipCallState state, int code) {
              if (mcvideo_closing(id, state) || mcvideo_call_state(id, state)) return;
              call_state(id, state, code);
            },
            [this](std::string_view dialog, const MediaDescription &media) {
              if (mcvideo_closing_.contains(std::string(dialog))) return;
              // The media of the MCVideo call's dialog: a refusal ends that call (mcvideo_end_call and its own
              // dialog), never the MCPTT call of dialog_.
              try {
                if (mcvideo_media(dialog, media)) return;
              } catch (const std::exception &error) {
                failure(error.what());
                mcvideo_end_call(error.what());
                schedule([this, id = std::string(dialog)] { sip_.bye(id); });
                return;
              }
              try {
                check_remote(media, private_media());
                if (media.floor_priority && (!local_.floor_priority || *media.floor_priority > *local_.floor_priority))
                  throw std::runtime_error("invalid negotiated floor priority");
                if (awaiting_answer_key_ && !answer_private_key(media)) return;
                remote_ = negotiated_media(media);
                implicit_floor_answered(); // TS 24.380 clause 6.2.4.2.2, once the 200 (OK) is in as well
                schedule([this] { subscribe_conference(); });
              } catch (const std::exception &error) {
                failure(error.what());
                if (!dialog_.empty()) schedule([this] { sip_.bye(dialog_); });
              }
            },
            {},
            [this](std::string_view error) { failure(std::string(error)); },
            {}}), // no ACK callback
      local_(local_media(cfg_)), audio_(cfg_.at("media").at("bind"), local_.audio_port),
      floor_(cfg_.at("media").at("bind"), local_.floor_port) {
  // TS 24.482 annex A.2.1.2: with a home HTTP proxy, the requests of http_ (CMS, GMS, content server, message store,
  // location) go through it; the IdMS's and the KMS's stay direct.
  http_.use_proxy(http_proxy(cfg_));
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  local_.protected_media = protected_media();
  if (cfg_.at("mcptt").contains("multicast"))
    throw std::runtime_error("mcptt.multicast is replaced by the MBMS procedures (the mbms block)");
  open_live_audio();
  if (mcvideo_enabled()) open_live_video();
  floor_timers_ = participant_timers();
  status_ = {{"sip_transport", disable_tls ? "tcp" : "tls"},
             {"instance_id", random_id()},
             {"role", "client"},
             {"user_id", user()},
             {"call_state", "idle"},
             {"sent_packets", 0},
             {"received_packets", 0},
             {"floor_denials", 0},
             {"floor_queue_info_received", 0}};
  mbms_configure();
  if (mbms_enabled_)
    status_.update({{"multicast_joined", ""}, {"multicast_listening", false}, {"multicast_received_packets", 0}});
}

//------------------------------------------------------------------------------
// TS 24.484 clause 9.3.1A: the MCVideo client reads its MCVideo user profile, of the
// profile index in use.
void Client::read_mcvideo_profile() {
  const auto index = profile_index_;
  const auto path = "/org.3gpp.mcvideo.user-profile/users/" + url_encode(mcvideo_user()) +
                    "/mcvideo-user-profile-" + std::to_string(index) + ".xml";
  auto response = http_.request("GET", cms_xcap_root() + path, {},
                                {{"Authorization", bearer()}});
  if (response.status != 200 || !response.headers.contains("etag"))
    throw std::runtime_error("MCVideo user profile retrieval failed: " + std::to_string(response.status));
  const auto profile = parse_mcvideo_profile(response.body);
  if (profile.user_id != mcvideo_user() || profile.profile_index != index)
    throw std::runtime_error("MCVideo user profile of another user or index");
  mcvideo_profile_ = {{"user_id", profile.user_id}, {"profile_index", profile.profile_index},
                      {"groups", profile.groups},   {"private_call_targets", profile.private_call_targets},
                      {"actions", profile.actions}, {"etag", response.headers.at("etag")}};
  status_["mcvideo_profile"] = mcvideo_profile_;
  event("document_retrieved", {{"resource", path}, {"bytes", response.body.size()}});
  event("mcvideo_profile_read", {{"groups", profile.groups.size()}, {"actions", profile.actions.size()}});
}

//------------------------------------------------------------------------------
// TS 24.484 clause 10.3.1A: the MCData client reads its MCData user profile, of the profile index in use; its
// ruleset holds the MCData rights (clause 10.3.2.1 item 11 b) that TS 24.282 has the client check.
void Client::read_mcdata_profile() {
  const auto index = profile_index_;
  const auto path = "/org.3gpp.mcdata.user-profile/users/" + url_encode(mcdata_user()) +
                    "/mcdata-user-profile-" + std::to_string(index) + ".xml";
  auto response = http_.request("GET", cms_xcap_root() + path, {},
                                {{"Authorization", bearer()}});
  if (response.status != 200 || !response.headers.contains("etag"))
    throw std::runtime_error("MCData user profile retrieval failed: " + std::to_string(response.status));
  const auto profile = parse_mcdata_profile(response.body);
  if (profile.user_id != mcdata_user() || profile.profile_index != index)
    throw std::runtime_error("MCData user profile of another user or index");
  mcdata_profile_actions_ = profile.actions;
  mcdata_profile_loaded_ = true;
  status_["mcdata_profile"] = {{"user_id", profile.user_id},
                               {"profile_index", profile.profile_index},
                               {"actions", profile.actions},
                               {"etag", response.headers.at("etag")}};
  event("document_retrieved", {{"resource", path}, {"bytes", response.body.size()}});
  event("mcdata_profile_read", {{"actions", profile.actions.size()}});
}

//------------------------------------------------------------------------------
// Helper: whether the MCVideo user profile read at start-up grants an action (an element of <actions> set to true,
// TS 24.484 clause 9.3.2).
bool Client::mcvideo_profile_allows(const std::string &action) const {
  const auto actions = mcvideo_profile_.find("actions");
  return actions != mcvideo_profile_.end() && actions->is_array() &&
         std::find(actions->begin(), actions->end(), action) != actions->end();
}

//------------------------------------------------------------------------------
// The client's life. TS 33.180 clause 5.1.3.1 steps A and B in parallel and clause 5.1.3.2.1: "the SIP REGISTER
// message shall not be delayed for lack of an access token", so the SIP registration starts first, without one
// (test view TS 37.579-1 table 5.3.2.2.1-1 steps 1 and 2), and the sign-in follows on the run loop (sign_in()). The
// service authorisation (TS 24.379 clause 7.2.2, or clause 7.2.1 with the token in a re-REGISTER) waits for both;
// until then the client is in the limited service state of TS 33.180 clause 5.1.1. Then the run loop until quit.
void Client::run() {
  sip_.set_body_filters(
      [this](const SipTransmission &transmission, std::vector<BodyPart> parts) {
        return protect_outgoing(transmission, std::move(parts));
      },
      [this](const SipTransmission &transmission, std::vector<BodyPart> parts) {
        return protect_incoming(transmission, std::move(parts));
      });
  sip_.start();
  register_and_authorize();
  schedule([this] { sign_in(); });
  while (!stopping) run_once();
  release();
  if (!dialog_.empty()) sip_.bye(dialog_);
  for (int index = 0; index < 20; index++) sip_.poll(std::chrono::milliseconds(10));
  save_audio();
  sip_.stop();
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.1.3.1 step A and clause 5.1.1: user authentication (TS 24.482 clause 6.2.1), on the worker
// thread (request_tokens) so that the SIP registration goes on meanwhile; then the rest of the sign-in on the run loop
// (sign_in_documents). A failed or unreachable sign-in leaves the client registered in the limited service state
// (status "service_state"), and the sign-in is tried again after oauth.sign_in_retry_ms (15 s: default pending
// maintainer choice (no spec value)); a token already obtained is kept for the retry.
void Client::sign_in() {
  // Logged off: no attempt before "log-on" (log_on_command), which signs in again when no attempt succeeded.
  if (signed_in_ || stopping || logged_off_) return;
  if (!token_.empty()) return sign_in_documents();
  request_tokens([this](std::exception_ptr error) {
    if (error) return sign_in_failed(error_text(error));
    event("oidc_authenticated", {{"user_id", user()}});
    schedule_token_renewal();
    sign_in_documents();
  });
}

//------------------------------------------------------------------------------
// The sign-in once the access token is held: the MCPTT user profile (TS 24.484 clause 8.3.1A) and group document
// (TS 24.481 clause 6.3.3.2.2), the MCVideo and MCData user profiles, the service configurations, and key
// provisioning and the CSKs (TS 33.180 clause 5.3.3 and clause 5.4); then the service authorisation once registered.
// With mcptt.documents_on_notify the MCPTT documents are read only on the NOTIFY of the configuration and group
// document subscriptions (TS 37.579-1 tables 5.3.2.6.1-1 and 5.3.2.7.1-1), not before the service authorisation.
void Client::sign_in_documents() {
  try {
    if (documents_on_notify())
      profile_index_ = configured_profile_index();
    else
      load_user_documents();
    if (mcvideo_enabled()) read_mcvideo_profile();
    if (mcdata_enabled()) read_mcdata_profile();
    std::vector<std::string> services;
    // With mcptt.documents_on_notify the MCPTT document comes with the configuration subscription, unless there is none.
    if (!documents_on_notify() || !cfg_.at("mcptt").value("subscribe_configuration", true)) services.push_back("mcptt");
    if (mcvideo_enabled()) services.push_back("mcvideo");
    if (mcdata_enabled()) services.push_back("mcdata");
    if (!services.empty()) read_service_configurations(services);
    // A document that could not be read leaves its service's signalling protection unknown, which is not "false":
    // this attempt fails and the next one reads it again.
    for (const auto &service : services)
      if (!protection_of(service))
        throw std::runtime_error("the " + service + " service configuration document could not be read");
    // mcptt.documents_on_notify: the MCPTT service configuration document comes with the configuration
    // subscription, which goes before the service authorisation (start_authorization); the keys wait for it.
    awaiting_mcptt_configuration_ = documents_on_notify() && !protection_of("mcptt");
    if (!awaiting_mcptt_configuration_) provision_for_protection();
  } catch (const std::exception &error) {
    return sign_in_failed(error.what());
  }
  signed_in_ = true;
  status_.erase("sign_in_error");
  event("signed_in", {{"registered", registered_}});
  if (registered_) start_authorization();
}

//------------------------------------------------------------------------------
// The CSKs whenever protection needs them: media or floor protection, and XML confidentiality or integrity protection
// of any service (TS 24.379 clause 7.2.2 step 3, TS 24.281 and TS 24.282 clause 7.2.2 step 3); a KMS failure then
// ends this sign-in attempt rather than letting protected signalling go out in the clear (throws).
void Client::provision_for_protection() {
  if (protected_media() || protected_floor() || xml_protection() || integrity_protection() ||
      (mcvideo_enabled() && (mcvideo_xml_protection() || mcvideo_integrity_protection())) || mcdata_needs_csk())
    provision_keys();
  else
    provision_identity_keys();
}

//------------------------------------------------------------------------------
// Signed in and registered: the service authorisation (TS 24.379 clause 7.2.2). With mcptt.documents_on_notify,
// first the configuration management subscription (TS 24.484 clause 6.3.13.2.2, authenticated by the access token),
// as the MCPTT service configuration document decides the protection of the authorisation (TS 24.379 clause
// 6.6.2.3.1); TS 37.579-2 table 5.1.3.2-1 runs the publication and both subscriptions in parallel, and TS 37.579-1
// table 5.3.2.5.1-1 NOTE 1 sends the PUBLISH after the documents are read. configuration_notified() goes on.
void Client::start_authorization() {
  if (!awaiting_mcptt_configuration_) return authorize();
  if (config_subscription_.empty()) return subscribe_configuration();
  // A later attempt (sign_in_failed) while the subscription stands: its refresh, which the CMS answers with a NOTIFY
  // of the documents' state (RFC 6665 clause 4.2.2).
  if (status_.value("configuration", Json::object()).value("subscription_status", 0) == 200)
    refresh_configuration_subscription();
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.1.1: a failed sign-in: the limited service state, and another attempt later.
void Client::sign_in_failed(const std::string &reason) {
  // The next attempt runs the whole sign-in again (sign_in returns at once while signed in), and nothing waits for the
  // MCPTT service configuration document meanwhile.
  signed_in_ = false;
  awaiting_mcptt_configuration_ = false;
  ++sign_in_failures_;
  status_["sign_in_error"] = reason;
  failure("sign-in: " + reason);
  const auto retry = cfg_.at("oauth").value("sign_in_retry_ms", 15000u);
  event("limited_service", {{"registered", registered_}, {"retry_ms", retry}});
  schedule_after(retry, [this] { sign_in(); });
}

//------------------------------------------------------------------------------
// RFC 3261 section 10.2: the SIP registration (TS 24.379 clause 7.2.1AA: with the feature tags of the client's
// services), at start-up and at log-on, then the service authorisation (TS 24.379 clause 7.2.2) once the user is
// signed in (TS 33.180 clause 5.1.3.2.1: the registration does not wait for the access token).
void Client::register_and_authorize() {
  sip_.register_account(register_expires(), [this](const SipResponse &response) {
    if (response.status_code < 200) return;
    if (response.status_code != 200) {
      failure("SIP registration: " + std::to_string(response.status_code));
      return;
    }
    registered_ = true;
    event("sip_registered", {{"signed_in", signed_in_}});
    if (signed_in_) schedule([this] { start_authorization(); });
  });
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.1.1: the service state of the status file: "full" when authorised, "limited" when registered
// with the SIP core but not (yet) authorised for the MC service, "none" before the registration.
std::string Client::service_state() const { return authorized_ ? "full" : registered_ ? "limited" : "none"; }

//------------------------------------------------------------------------------
// TS 24.379, TS 24.281 and TS 24.282 clause 6.2.8.1.15: the client reads the service configuration document of each
// of its services (TS 24.484 clauses 8.4.2.8, 9.4.2.8, 10.4.2.8) for the Resource-Priority values of its requests;
// all of them when none is named.
// A document that cannot be read leaves the service without them: its emergency and imminent peril requests fail.
void Client::read_service_configurations(std::vector<std::string> services) {
  if (services.empty()) {
    services.push_back("mcptt");
    if (mcvideo_enabled()) services.push_back("mcvideo");
    if (mcdata_enabled()) services.push_back("mcdata");
  }
  for (const auto &service : services) {
    const auto path = "/org.3gpp." + service + ".service-config/global/service-config.xml";
    try {
      auto response = http_.request("GET", cms_xcap_root() + path, {},
                                    {{"Authorization", bearer()}});
      if (response.status != 200) throw std::runtime_error("HTTP " + std::to_string(response.status));
      service_priorities_[service] = parse_service_resource_priorities(response.body);
      service_protection_[service] = parse_service_protection(response.body);
      event("document_retrieved", {{"resource", path}, {"bytes", response.body.size()}});
    } catch (const std::exception &error) {
      event("service_configuration_unavailable", {{"service", service}, {"reason", error.what()}});
    }
  }
  status_["service_configuration"] = service_priorities_;
  for (const auto &[service, protection] : service_protection_)
    status_["service_protection"][service] = {{"confidentiality", protection.confidentiality},
                                              {"integrity", protection.integrity}};
}

//------------------------------------------------------------------------------
// Helper: the Resource-Priority values of a service by level, as JSON ("imminent_peril" as well, MCData's name).
Json Client::service_priorities(const std::string &service) const {
  Json out = Json::object();
  if (const auto found = service_priorities_.find(service); found != service_priorities_.end())
    for (const auto &[level, value] : found->second) out[level] = value;
  if (out.contains("imminent")) out["imminent_peril"] = out["imminent"];
  return out;
}

//------------------------------------------------------------------------------
// TS 24.484 clause 7.2.2.1: the profile index of <Default-user-profile> (mcptt.profile_index of the activation).
unsigned Client::configured_profile_index() const {
  const auto mcptt = cfg_.value("mcptt", nlohmann::json::object());
  const auto value = mcptt.value("profile_index", nlohmann::json());
  unsigned index = 1;
  if (value.is_number_unsigned()) index = value.get<unsigned>();
  if (value.is_string()) {
    try {
      index = static_cast<unsigned>(std::stoul(value.get<std::string>()));
    } catch (const std::exception &) {
      index = 1;
    }
  }
  return index >= 1 && index <= 255 ? index : 1;
}

//------------------------------------------------------------------------------
// TS 24.484 clause 8.3.1A and TS 24.481 clause 6.3.3.2.2: retrieve the MCPTT user profile and then the group
// document at start-up, keeping the profile's actions, groups, ETag and index.
void Client::load_user_documents() {
  const auto retrieve = [this](const std::string &base, const std::string &resource) {
    auto response = http_.request("GET", base + resource, {}, {{"Authorization", bearer()}});
    if (response.status != 200 || response.body.empty() || !response.headers.contains("etag"))
      throw std::runtime_error("MCPTT profile or group retrieval failed");
    event("document_retrieved", {{"resource", resource}, {"bytes", response.body.size()}});
    return response;
  };
  profile_index_ = configured_profile_index();
  const auto profile = retrieve(cms_xcap_root(), "/" + profile_document_path());
  profile_actions_ = parse_profile_actions(profile.body);
  take_profile_groups(profile.body);
  status_["configuration"]["etag"] = profile.headers.at("etag");
  profile_loaded_ = true;
  profile_index_ = parse_profile_index(profile.body);
  group_from_profile();
  // A user may belong to no group (an account activated without one): then there
  // is no group document to read and nothing to affiliate to.
  if (!group().empty())
    retrieve(gms_xcap_root(), "/org.openmobilealliance.groups/global/byGroupID/" + url_encode(group()));
}

//------------------------------------------------------------------------------
// Helper: mcptt.group_from_profile (a dispatcher position, off unless configured): a configured group the user
// profile does not list among its groups (TS 24.484 clause 8.3.2) gives way to the profile's first group, or to
// none.
void Client::group_from_profile() {
  if (!cfg_.at("mcptt").value("group_from_profile", false) ||
      std::find(profile_groups_.begin(), profile_groups_.end(), group()) != profile_groups_.end())
    return;
  const auto chosen = profile_groups_.empty() ? std::string{} : profile_groups_.front();
  event("group_from_profile", {{"configured", group()}, {"group", chosen}});
  cfg_["mcptt"]["group_id"] = chosen;
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.3.3 and clause 5.4: key provisioning from the KMS (MCPTT, MCData and MCVideo identities) and
// the CSKs this client uploads at service authorisation.
void Client::provision_keys() {
  const auto key_warning = [](std::string_view text) { event("key_warning", {{"reason", text}}); };
  keys_ = std::make_unique<KeyClient>(kms_http_, cfg_.at("security"), user());
  keys_->set_warning_handler(key_warning);
  keys_->provision(access_token());
  status_["key_management"] = {{"kms_uri", cfg_.at("security").at("kms_uri")},
                               {"home_kms_uri", cfg_.at("security").value("home_kms_uri", "")},
                               {"own_kms", keys_->kms_uri_of(user())}};
  // TS 24.282 clause 6.6: the MCData client signs and receives PCKs as its MCData ID.
  if (mcdata_protected() || mcdata_needs_csk() || mcdata_authenticates()) {
    mcdata_keys_ = std::make_unique<KeyClient>(kms_http_, cfg_.at("security"), mcdata_user(), user());
    mcdata_keys_->set_warning_handler(key_warning);
    mcdata_keys_->provision(access_token());
  }
  if (mcdata_needs_csk()) {
    auto [csk, upload] = mcdata_keys_->make_csk(mcdata_service());
    mcdata_csk_ = csk;
    mcdata_csk_upload_ = std::move(upload);
  }
  // TS 24.281 clause 4.7 with TS 33.180 clause 9.4: the MCVideo client keys as its MCVideo ID, and
  // uploads its own CSK for transmission control at MCVideo service authorisation.
  if (mcvideo_enabled()) {
    mcvideo_keys_ = std::make_unique<KeyClient>(kms_http_, cfg_.at("security"), mcvideo_user(), user());
    mcvideo_keys_->set_warning_handler(key_warning);
    mcvideo_keys_->provision(access_token());
    auto [video_csk, video_upload] = mcvideo_keys_->make_csk(mcvideo_service_uri());
    mcvideo_protection_.set_floor(video_csk);
    mcvideo_csk_upload_ = std::move(video_upload);
    mcvideo_csk_ = video_csk;
    status_["mcvideo_csk_id"] = video_csk.csb_id;
  }
  auto [csk, upload] = keys_->make_csk(service());
  protection_.set_floor(csk);
  csk_upload_ = std::move(upload);
  csk_ = csk;
  status_["csk_id"] = csk.csb_id;
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.3.3 and annex D (test view TS 37.579-1 table 5.3.2.3.1-1 steps 11 to 14): KMS initialisation
// and key provisioning with the access token at sign-in, also without protected media or floor control; no CSK is
// made then. A KMS that cannot be reached leaves the unprotected client signed in.
void Client::provision_identity_keys() {
  const auto security = cfg_.value("security", Json::object());
  if (security.value("kms_uri", std::string{}).empty()) return;
  try {
    keys_ = std::make_unique<KeyClient>(kms_http_, security, user());
    keys_->provision(access_token());
    status_["key_management"] = {{"kms_uri", security.at("kms_uri")},
                                 {"home_kms_uri", security.value("home_kms_uri", "")},
                                 {"own_kms", keys_->kms_uri_of(user())}};
  } catch (const std::exception &error) {
    keys_.reset();
    event("key_management_unavailable", {{"reason", error.what()}});
  }
}

//------------------------------------------------------------------------------
// Helper: one pass of the run loop: SIP events, the worker thread's results, due and scheduled tasks, control commands, floor and media packets,
// the MCVideo media and the periodic tick.
void Client::run_once() {
  sip_.poll(std::chrono::milliseconds(5));
  // The results of the worker thread (token grants) run as scheduled tasks.
  for (auto &task : handoff_.take()) pending_.push_back(std::move(task));
  for (auto due = timed_.begin(); due != timed_.end() && due->first <= monotonic_ms(); due = timed_.erase(due))
    pending_.push_back(std::move(due->second));
  auto tasks = std::move(pending_);
  pending_.clear();
  for (auto &fn : tasks) try {
      fn();
    } catch (const std::exception &error) {
      failure(error.what());
    }
  for (auto &line : control_.read()) try {
      command(line);
    } catch (const std::exception &error) {
      failure(error.what());
    }
  for (int index = 0; index < 32; index++) {
    auto datagram = floor_.receive();
    if (!datagram) break;
    try {
      floor_packet(*datagram);
    } catch (const std::exception &error) {
      status_["floor_rejected_packets"] = status_.value("floor_rejected_packets", 0ULL) + 1;
      event("floor_rejected", {{"reason", error.what()}});
    }
  }
  receive_live_audio();
  if (mcvideo_enabled()) receive_live_video();
  multicast_poll();
  mbms_poll();
  mcvideo_multicast_poll();
  for (int index = 0; index < 32; index++) {
    auto datagram = audio_.receive();
    if (!datagram) break;
    try {
      audio_packet(*datagram);
    } catch (const std::exception &error) {
      status_["media_rejected_packets"] = status_.value("media_rejected_packets", 0ULL) + 1;
      event("media_rejected", {{"reason", error.what()}});
    }
  }
  if (mcvideo_enabled()) mcvideo_poll();
  try {
    tick();
  } catch (const std::exception &error) {
    stop_protected_call(error.what());
  }
}

} // namespace mcx
