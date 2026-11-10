//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The MCPTT client's pre-established session: establishment (TS 24.379 clause 8.2.1), modification by the user
// (clause 8.3.1.1) or by the participating function (clause 8.3.1.2), release by either side (clauses 8.4.1.1 and
// 8.4.1.2), with ICE (clause 4.9, RFC 8445: the client is the controlling agent, as the offerer) on the client's
// speech and floor sockets and an RTCP socket, and the call setup control state machine of TS 24.380 clause 9.2.2.
// Calls over the session (REFER, Connect) build on this (conformance/pre-est-feat.md M2 and M3).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), TS 37.579-2 V18.1.0 (Rel-18), RFC 8445,
// RFC 8839, RFC 3311.

#include "../core/client.hpp"

#include <arpa/inet.h>

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 8.2.1 step 1 (RFC 8445 section 5.1.1.1): the client's ICE agent, controlling, with host
// candidates on its speech RTP, RTCP and floor control sockets.
std::unique_ptr<IceAgent> Client::pre_session_agent(const MediaDescription &local) {
  IceLocalSockets sockets{local.address, local.audio_port, local.rtcp_port,
                          local.control == MediaControl::none ? std::uint16_t{0} : local.floor_port};
  return std::make_unique<IceAgent>(true, sockets,
                                    [this](IceStream stream, unsigned component, std::span<const std::uint8_t> packet,
                                           const std::string &host, std::uint16_t port) {
                                      if (stream == IceStream::Control)
                                        floor_.send(packet, host, port);
                                      else if (component == 2 && pre_session_ && pre_session_->rtcp)
                                        pre_session_->rtcp->send(packet, host, port);
                                      else
                                        audio_.send(packet, host, port);
                                    });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 8.2.1: "pre-establish [implicit]": an initial INVITE to the PSI identifying pre-established session
// set-up (clause 6.3.1.1; mcptt.pre_established_uri) with an SDP offer only (steps 1 to 10; TS 37.579-1 table
// 5.3.3.4-1: Content-Type application/sdp): the client's speech and floor control media, an implicit floor request
// when asked (TS 24.379 clause 6.4), the RTCP port and the ICE candidates (RFC 8839). The SIP engine adds the
// feature tags, Accept-Contact, P-Preferred-Service, Supported "timer" and Session-Expires (steps 3 to 8).
void Client::pre_establish_command(const std::string &cmd) {
  if (!authorized_) throw std::runtime_error("pre-established session setup requires service authorisation");
  if (pre_session_) throw std::runtime_error("a pre-established session already exists");
  const auto &mcptt = cfg_.at("mcptt");
  const auto target = mcptt.value("pre_established_uri", service() + ";mcptt-pre-established");
  auto session = std::make_unique<PreSession>();
  session->local = local_;
  session->local.floor_priority.reset();
  session->local.floor_ssrc = random_ssrc();
  session->local.granted_audio_ssrc = 0;
  session->local.implicit_request = cmd.ends_with(" implicit");
  session->local.rtcp_port = static_cast<std::uint16_t>(local_.audio_port + 1);
  session->rtcp = std::make_unique<UdpSocket>(cfg_.at("media").value("bind", std::string("0.0.0.0")),
                                              session->local.rtcp_port);
  session->control = std::make_unique<PreEstablishedClient>(session->local.floor_ssrc);
  session->ice = pre_session_agent(session->local);
  session->local.ice = session->ice->local_description();
  pre_session_ = std::move(session);
  // TS 37.579-1 table 5.3.3.4-1: Accept "application/sdp" only (no MCPTT-Info is offered or expected).
  pre_session_->dialog = sip_.invite(
      target, pre_session_->local, {}, [this](const SipResponse &response) { pre_session_answered(response); },
      {{"Accept", "application/sdp"}});
  pre_session_status();
  event("pre_session_requested", {{"dialog", pre_session_->dialog}, {"implicit", pre_session_->local.implicit_request}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 8.2.1: the final response to the INVITE: a 2xx gives the URI identifying the session (its Contact,
// TS 24.379 clause 8.2.2 step 9 a); the negotiated media come with on_media (pre_session_media). A failure ends it.
void Client::pre_session_answered(const SipResponse &response) {
  if (!pre_session_) return;
  if (response.status_code / 100 == 2) {
    pre_session_->uri = contact_uri_of(get_header(response.headers, "Contact"));
    return;
  }
  if (response.status_code < 300) return;
  status_["last_call_failure"] = {{"status", response.status_code},
                                  {"warning", std::string(get_header(response.headers, "Warning"))}};
  event("pre_session_failed", {{"status", response.status_code}});
  pre_session_.reset();
  pre_session_status();
}

//------------------------------------------------------------------------------
// TS 24.379 clause 8.2.1 ("Upon receiving a SIP 2xx response ... shall interact with the media plane") and clauses
// 8.3.1.1 and 8.3.1.2: the session's negotiated media; the first answer starts the ICE checks (RFC 8445 section
// 6.1.4); new credentials in a later offer or answer restart them (section 9).
bool Client::pre_session_media(std::string_view dialog, const MediaDescription &media) {
  if (!pre_session_ || dialog != pre_session_->dialog) return false;
  const auto previous = pre_session_->remote && pre_session_->remote->ice ? pre_session_->remote->ice->ufrag
                                                                          : std::string{};
  pre_session_->remote = media;
  if (media.ice && pre_session_->ice) {
    if (pre_session_->ice->state() == IceState::Gathered) {
      pre_session_->ice->start(*media.ice);
    } else if (media.ice->ufrag != previous) {
      pre_session_->ice = pre_session_agent(pre_session_->local);
      pre_session_->local.ice = pre_session_->ice->local_description();
      pre_session_->ice->start(*media.ice);
    }
  }
  pre_session_status();
  return true;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 9.2.2.2.2 (the session's dialog confirmed: 'U: Pre-established session not in use') and clause
// 9.2.2.3.3 (ended: 'Start-stop'; TS 24.379 clauses 8.4.1.1 step 4 and 8.4.1.2 step 2 a).
bool Client::pre_session_state(std::string_view dialog, SipCallState state) {
  if (!pre_session_ || dialog != pre_session_->dialog) return false;
  if (state == SipCallState::confirmed && pre_session_->state == "establishing") {
    pre_session_->state = "established";
    pre_session_->control->started();
    event("pre_session_established", {{"dialog", pre_session_->dialog}, {"session_uri", pre_session_->uri}});
  } else if (state == SipCallState::disconnected) {
    pre_session_->control->stopped();
    event("pre_session_released", {{"dialog", pre_session_->dialog}});
    pre_session_.reset();
  }
  pre_session_status();
  return true;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 8.3.1.1: "pre-session-modify [implicit|explicit] [update]": the user modifies the session outside
// a call: an SDP offer with the session's media, asking (or no longer asking) for implicit floor control (TS 24.379
// clause 6.4; TS 37.579-2 test 5.4 step 4a1), in a re-INVITE or, with "update", an UPDATE (RFC 3311). The client keeps
// the previous media until the 200 (OK) (NOTE).
void Client::pre_session_modify_command(const std::string &cmd) {
  if (!pre_session_ || pre_session_->state != "established")
    throw std::runtime_error("no established pre-established session to modify");
  auto offer = pre_session_->local;
  if (cmd.find(" implicit") != std::string::npos) offer.implicit_request = true;
  if (cmd.find(" explicit") != std::string::npos) offer.implicit_request = false;
  const bool update = cmd.find(" update") != std::string::npos;
  auto done = [this, offer, update](const SipResponse &response) {
    event("pre_session_modified", {{"method", update ? "UPDATE" : "INVITE"}, {"status", response.status_code}});
    if (pre_session_ && response.status_code / 100 == 2) pre_session_->local.implicit_request = offer.implicit_request;
    pre_session_status();
  };
  if (update)
    sip_.update(pre_session_->dialog, offer, done);
  else
    sip_.reinvite(pre_session_->dialog, offer, {}, done);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 8.4.1.1: "pre-session-release": BYE in the session's dialog (steps 1 to 3); its end releases the
// media plane resources (step 4, pre_session_state).
void Client::pre_session_release_command() {
  if (!pre_session_) throw std::runtime_error("no pre-established session to release");
  pre_session_->state = "releasing";
  sip_.bye(pre_session_->dialog);
  pre_session_status();
}

//------------------------------------------------------------------------------
// TS 24.379 clause 8.3.1.2: an UPDATE or re-INVITE from the participating function modifying the session outside a
// call: 488 without an acceptable offer (step 1; parse_sdp refuses an offer without the MCPTT speech codec), otherwise
// 200 (OK) with the answer (step 2 a, clause 6.2.2): the session's media, the offered floor priority taken (TS 24.380
// clause 14.3.3), the implicit floor request kept, and the ICE attributes. Clause 8.4.1.2: its BYE, with no call over
// the session, is answered 200 (OK) (step 2 b); the dialog's end releases the media plane resources.
std::optional<SipResponse> Client::pre_session_request(const SipMessage &message) {
  if (!pre_session_ || message.dialog_id != pre_session_->dialog) return std::nullopt;
  SipResponse response;
  response.status_code = 200;
  if (message.method == "BYE") return response;
  if (message.method != "INVITE" && message.method != "UPDATE") {
    response.status_code = 405;
    return response;
  }
  if (!message.remote_media || message.remote_media->control != pre_session_->local.control) {
    response.status_code = 488;
    event("pre_session_offer_refused", {{"method", message.method}});
    return response;
  }
  auto answer = pre_session_->local;
  answer.floor_priority = message.remote_media->floor_priority;
  pre_session_->local.floor_priority = answer.floor_priority;
  response.local_media = answer;
  event("pre_session_offer_answered",
        {{"method", message.method},
         {"floor_priority", answer.floor_priority ? Json(*answer.floor_priority) : Json(nullptr)}});
  return response;
}

//------------------------------------------------------------------------------
// RFC 8445 section 7.3 with RFC 7983 section 7: a STUN datagram of the session's sockets goes to its ICE agent.
bool Client::pre_session_stun(IceStream stream, unsigned component, const Datagram &datagram) {
  if (!pre_session_ || !pre_session_->ice || !is_stun_packet(datagram.bytes)) return false;
  std::array<char, INET_ADDRSTRLEN> host{};
  inet_ntop(AF_INET, &datagram.source.sin_addr, host.data(), host.size());
  pre_session_->ice->receive(stream, component, datagram.bytes, host.data(), ntohs(datagram.source.sin_port));
  return true;
}

//------------------------------------------------------------------------------
// The session's RTCP socket (STUN only outside a call; the speech and floor sockets are read by run_once) and the ICE
// agent's timers; the ICE outcome is reported once.
void Client::pre_session_poll() {
  if (!pre_session_) return;
  if (pre_session_->rtcp)
    for (int index = 0; index < 32; ++index) {
      auto datagram = pre_session_->rtcp->receive();
      if (!datagram) break;
      (void)pre_session_stun(IceStream::Audio, 2, *datagram);
    }
  if (!pre_session_ || !pre_session_->ice) return;
  const auto before = pre_session_->ice->state();
  pre_session_->ice->tick();
  if (pre_session_->ice->state() != before && pre_session_->ice->state() != IceState::Checking) {
    event("pre_session_ice", {{"state", pre_session_->ice->state() == IceState::Completed ? "completed" : "failed"},
                              {"needs_updated_offer", pre_session_->ice->needs_updated_offer()}});
    pre_session_status();
  }
}

//------------------------------------------------------------------------------
// The status file's "pre_session": the session's state, URI, ICE state and whether it asks for implicit floor control.
void Client::pre_session_status() {
  if (!pre_session_) {
    status_["pre_session"] = nullptr;
    return;
  }
  const auto ice = !pre_session_->ice                                      ? "none"
                   : pre_session_->ice->state() == IceState::Completed     ? "completed"
                   : pre_session_->ice->state() == IceState::Failed        ? "failed"
                   : pre_session_->ice->state() == IceState::Checking      ? "checking"
                                                                           : "gathered";
  status_["pre_session"] = {{"state", pre_session_->state},
                            {"uri", pre_session_->uri},
                            {"dialog", pre_session_->dialog},
                            {"ice", ice},
                            {"implicit", pre_session_->local.implicit_request}};
}

} // namespace mcx
