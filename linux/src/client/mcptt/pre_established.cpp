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

#include "mcx/refer.hpp"

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
  // TS 24.380 clause 14.2.3: "mc_priority" when a floor priority other than the default is required, based on the
  // configured value (configured_floor_priority: mcptt.floor_priority, or the selected group's <user-priority>), as
  // in an on-demand call's offer; none configured: no "mc_priority". TS 37.579-1 table 5.5.3.1.1-1 and the TS 37.579
  // test suite expect it (any value 1..255) in every MCPTT offer, this one included.
  session->local.floor_priority.reset();
  if (const auto priority = configured_floor_priority()) {
    if (*priority > 99) throw std::runtime_error("maximum floor priority must be 0..99");
    session->local.floor_priority = static_cast<std::uint8_t>(*priority);
  }
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
  // TS 24.380 clause 14.2.4: "mc_granted" in the offer of the initial INVITE, "when it is acceptable for the MCPTT
  // client to receive a granted indication in the SIP 200 (OK) response" (it is; the floor is only ever granted for an
  // implicit request, TS 24.380 clause 14.3.4). Clause 14.5: never in a later offer, so only the INVITE's copy has
  // it, not the session's media the later offers start from. TS 37.579-1 table 5.5.3.1.1-1 (INITIAL_SDP_OFFER) and
  // the TS 37.579 test suite expect it here.
  auto offer = pre_session_->local;
  offer.granted = true;
  // TS 37.579-1 table 5.3.3.4-1: Accept "application/sdp" only (no MCPTT-Info is offered or expected).
  pre_session_->dialog = sip_.invite(
      target, offer, {}, [this](const SipResponse &response) { pre_session_answered(response); },
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
  // RFC 3264 section 8.2: a call over the session without floor control keeps the session's control channel.
  pre_session_->remote = session_media_keeping_control(media, pre_session_->remote);
  // TS 24.380 clause 4.3: the Acknowledge carries the SSRC of the participating function's "mc_floor_ssrc", or, from
  // one without multiplexing (none: TS 24.380 clause 14.4, the TS 37.579 test system), this client's own
  // (client_rules::floor_send_ssrc).
  pre_session_->control->set_ssrc(
      client_rules::floor_send_ssrc(pre_session_->local.floor_ssrc, pre_session_->remote->floor_ssrc));
  if (media.ice && pre_session_->ice) {
    if (pre_session_->ice->state() == IceState::Gathered) {
      pre_session_->ice->start(*pre_session_->remote->ice);
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
    // TS 24.380 clause 4.1.2.3: releasing the session releases the call over it (TS 37.579-2 test 6.2.11 step 8).
    if (call_over_pre_session()) call_state(dialog_, SipCallState::disconnected, 200);
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
  const auto info_part = std::find_if(message.parts.begin(), message.parts.end(),
                                      [](const BodyPart &part) { return part.content_type == mcptt_mime; });
  // TS 24.379 clause 6.2.8.1.16 (clause 10.1.2.2.2.1, last paragraphs): a re-INVITE in the session targeted by this
  // client's REFER for an emergency or imminent peril call: the priority state the MCPTT-Info gives, and 200 (OK)
  // with an SDP answer "based upon the parameters already negotiated for the pre-established session" (step 4).
  // TS 24.379 clause 6.3.2.1.8.7 (clause 11.1.1.3.1.2, 2xx step 3): during this client's first-to-answer call over
  // the session, a re-INVITE whose offer carries the answering invitee's MIKEY-SAKKE I_MESSAGE: the PCK, 200 (OK)
  // with the session's media; the Connect follows (TS 37.579-2 test 6.2.22 steps 7 to 10B).
  if (message.method == "INVITE" && call_over_pre_session() && message.remote_media && first_to_answer_ &&
      awaiting_answer_key_ && !message.remote_media->key_mgmt.empty()) {
    if (!answer_private_key(*message.remote_media)) {
      response.status_code = 488;
      response.headers = {{"Warning", mikey_failed}};
      return response;
    }
    response.local_media = pre_session_->local;
    response.local_media->key_mgmt.clear();
    event("first_to_answer_key_reinvite", {{"pck_id", status_.value("private_key_id", Json(nullptr))}});
    return response;
  }
  if (message.method == "INVITE" && call_over_pre_session() && message.remote_media) {
    if (info_part != message.parts.end())
      update_priority(parse_mcptt_info(info_part->body), parse_emergency_location_parts(message.parts));
    response.local_media = pre_session_->local;
    event("pre_session_priority_reinvite",
          {{"resource_priority", std::string(get_header(message.headers, "Resource-Priority"))}});
    return response;
  }
  // TS 24.379 clause 10.1.1.2.2.2: a re-INVITE with MCPTT-Info offers a call over the session (manual commencement).
  if (message.method == "INVITE" && info_part != message.parts.end()) return pre_session_call_offer(message);
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

//------------------------------------------------------------------------------
// Helper: an established pre-established session, not in use (TS 24.380 clause 9.2.2.3), that calls go over
// (mcptt.calls_over_pre_established, by default true).
bool Client::pre_session_usable() const {
  return pre_session_ && pre_session_->state == "established" && pre_session_->remote &&
         pre_session_->control->state() == PreEstablishedClientState::NotInUse &&
         cfg_.at("mcptt").value("calls_over_pre_established", true);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.2.1 (prearranged) with clause 10.1.2.2.2.1 (chat) steps 1 to 12, clause 11.1.1.2.2.1
// (private, steps 1 to 8): the REFER outside a dialog to the session's identity: Refer-To a cid URL naming the resource
// list whose single entry is the target (the group, or the called user), with the entry's header fields (the two
// Accept-Contact of a group call; the Answer-Mode or Priv-Answer-Mode of a private call) and the hname "body" holding
// the MCPTT-Info (clause 6.6.2.1: its sensitive content encrypted under the CSK) and, with_sdp, the SDP offer (the
// session's media: an implicit floor request, clause 6.4; a private call without floor control or protected end to
// end, clause 11.1.1.2.2.1 step 8 c i); Refer-Sub "false", Supported "norefersub", Require "multiple-refer",
// Target-Dialog, P-Preferred-Service (refer.hpp); To the URI of the session's INVITE (TS 37.579-1 table 5.5.2.12-1).
// The call's media are the session's. Returns the call's local identifier ("pre-call-..."), in place of a dialog.
std::string Client::pre_session_refer_call(const std::vector<std::string> &targets, std::vector<BodyPart> parts,
                                           std::vector<SipHeader> entry_headers, const std::vector<SipHeader> &headers,
                                           bool with_sdp) {
  auto &session = *pre_session_;
  const auto ids = sip_.dialog_identifiers(session.dialog);
  if (!ids) throw std::runtime_error("the pre-established session's dialog is gone");
  // The call's remote media come with the Connect (pre_session_connected), as an on-demand call's with its 200 (OK):
  // until then no floor participant runs (its 'U: has no permission' would drop the implicit request's speech).
  pre_session_implicit_ = local_.implicit_request;
  local_.floor_ssrc = session.local.floor_ssrc;
  auto offer = local_;
  offer.ice = session.local.ice;
  if (offer.ice && offer.control == MediaControl::none) offer.ice->control.clear();
  offer.rtcp_port = session.local.rtcp_port;

  if (xml_protection())
    for (auto &part : parts)
      if (mcptt_xml_part(part.content_type)) part.body = xmlsec::encrypt(part.body, xml_key(), mcptt_xml_profile());
  // Clause 11.1.1.2.2.1 step 9: a first-to-answer call has one entry per targeted user, each with the same body.
  std::vector<ReferEntry> entries;
  for (const auto &target : targets) {
    ReferEntry entry{target, entry_headers, {}};
    if (with_sdp) entry.body.push_back({"application/sdp", make_sdp(offer, 1), random_id().substr(0, 16) + "@mcx"});
    for (auto part : parts) {
      if (part.content_id.empty()) part.content_id = random_id().substr(0, 16) + "@mcx";
      entry.body.push_back(std::move(part));
    }
    entries.push_back(std::move(entry));
  }
  const auto content_id = random_id().substr(0, 16) + "@mcx";
  auto refer_headers = pre_established_refer_headers(cid_refer_to(content_id), *ids);
  const auto &mcptt = cfg_.at("mcptt");
  refer_headers.push_back({"To", "<" + mcptt.value("pre_established_uri", service() + ";mcptt-pre-established") + ">"});
  for (const auto &header : headers)
    if (header.name == "Resource-Priority") refer_headers.push_back(header); // clause 10.1.2.2.2.1 steps 5 and 7
  const auto call = "pre-call-" + random_id();
  sip_.refer(session.uri, {refer_resource_list_part(entries, content_id, "mcx-entry")}, refer_headers,
             [this, call](const SipResponse &response) {
               if (dialog_ != call || !pre_session_) return;
               if (response.status_code / 100 == 2) {
                 // TS 24.379 clause 11.1.6.2.2.3 step 1 b: an ambient listening call's release indicator.
                 if (ambient_ && ambient_->dialog == call)
                   ambient_->release_allowed =
                       get_header(response.headers, "Feature-Caps").find("g.3gpp.mcptt.ambient-listening-call-release") !=
                       std::string_view::npos;
                 // TS 24.380 clause 9.2.2.3.5: 'U: Pre-established session in use'; the Connect follows.
                 pre_session_->control->call_refer_accepted();
                 event("pre_session_refer_accepted", {{"status", response.status_code}});
               } else if (response.status_code >= 300) {
                 status_["last_call_failure"] = {{"status", response.status_code},
                                                 {"warning", std::string(get_header(response.headers, "Warning"))}};
                 failure("call setup over the pre-established session: " + std::to_string(response.status_code));
                 report_followup(false); // TS 24.379 clause 11.1.7.2.2: a remotely initiated call failed
                 clear_call_state();
               }
               pre_session_status();
             });
  event("pre_session_refer_sent", {{"call", call}, {"targets", targets}});
  return call;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.4.2 (clause 10.1.1.2.3.2): the user leaves the call keeping the session: REFER outside a
// dialog to the session's identity with Refer-To the MCPTT session identity and method "BYE", Refer-Sub "false",
// Supported "norefersub", Target-Dialog (steps 2 to 10). On its 2xx: 'U: Pre-established session not in use' and the
// call ends (TS 24.380 clause 9.2.2.4.6).
void Client::pre_session_leave() {
  if (!pre_session_) return clear_call_state();
  const auto ids = sip_.dialog_identifiers(pre_session_->dialog);
  if (!ids || session_uri_.empty()) return clear_call_state();
  auto headers = pre_established_refer_headers(bye_refer_to(session_uri_), *ids);
  headers.push_back({"To", "<" + cfg_.at("mcptt").value("pre_established_uri", service() + ";mcptt-pre-established") +
                               ">"});
  const auto call = dialog_;
  sip_.refer(pre_session_->uri, {}, headers, [this, call](const SipResponse &response) {
    if (response.status_code < 200) return;
    if (pre_session_) pre_session_->control->leave_refer_accepted();
    event("pre_session_call_left", {{"status", response.status_code}});
    if (dialog_ == call) call_state(call, SipCallState::disconnected, response.status_code);
    pre_session_status();
  });
}

//------------------------------------------------------------------------------
// TS 24.380 clauses 8.3 and 13.3.3: an Acknowledge on the session's control channel, protected as this client's
// floor control is.
void Client::pre_session_send(const PreEstablishedMessage &message) {
  if (!pre_session_ || !pre_session_->remote) return;
  auto bytes = encode_pre_established(message);
  if (protected_floor()) bytes = protection_.protect_floor(bytes);
  const auto &remote = *pre_session_->remote;
  floor_.send(bytes, server_target(remote.floor_address, remote.floor_port), remote.floor_port);
  event("pre_session_acknowledge_sent",
        {{"reason_code", static_cast<unsigned>(message.reason_code.value_or(PreEstablishedReasonCode::Accepted))}});
}

//------------------------------------------------------------------------------
// TS 24.380 clause 4.1.2.1: a datagram from the participating function's control port of the session: opened once
// (TS 24.380 clause 13.3.3), a pre-established session call control message goes to the state machine of clause
// 9.2.2; floor control of the call over the session to the floor participant (clause 6.2.4). False when the
// datagram is not the session's.
bool Client::pre_session_floor(const Datagram &datagram) {
  if (!pre_session_ || !pre_session_->remote) return false;
  const auto &remote = *pre_session_->remote;
  if (!sent_by_server(datagram.source, remote.floor_address, remote.floor_port)) return false;
  // An on-demand call (its own ports at the server) is not the session's.
  if (!dialog_.empty() && !call_over_pre_session()) return false;
  // TS 24.380 clauses 4.3.3.1 and 14.4: the SSRC this client allocated, or any from a participating function without
  // multiplexing (client_rules::floor_ssrc_accepted; the TS 37.579 test system sends with its own).
  const auto local_ssrc = pre_session_->local.floor_ssrc;
  if (!client_rules::floor_ssrc_accepted(wire_ssrc(datagram.bytes, true), local_ssrc, remote.floor_ssrc)) return true;
  const auto bytes = protected_floor() ? protection_.unprotect_floor(datagram.bytes) : datagram.bytes;
  if (is_pre_established_message(bytes)) {
    if (const auto message = decode_pre_established(bytes)) pre_session_message(*message);
    return true;
  }
  if (!call_over_pre_session() || !remote_) return true; // floor control outside a call: nothing to do
  auto packet = decode_floor(bytes);
  if (client_rules::floor_ssrc_accepted(packet.ssrc, local_.floor_ssrc, remote.floor_ssrc)) floor_message(packet, false);
  return true;
}

//------------------------------------------------------------------------------
// TS 24.380 clauses 9.2.2.3.2, 9.2.2.4.2, 9.2.2.3.4 and 9.2.2.4.5: a Connect or Disconnect, acknowledged as the state
// machine says. A Connect to this client's own REFER confirms its call (TS 24.380 clause 4.1.2.2); a Connect without
// a call of this client offers one (automatic commencement: accepted, unless another call is ongoing: 'Busy'); a
// Disconnect in use ends the call over the session (clause 4.1.2.3).
void Client::pre_session_message(const PreEstablishedMessage &message) {
  auto &control = *pre_session_->control;
  event("pre_session_control_received",
        {{"type", message.type == PreEstablishedMessageType::Connect ? "Connect" : "Disconnect"},
         {"ack_required", message.ack_required},
         {"session_identity", message.session_identity}});
  if (message.type == PreEstablishedMessageType::Connect) {
    const bool own_call = call_over_pre_session() &&
                          (status_.value("call_state", "") == "calling" || std::exchange(pre_session_answering_, false));
    auto answer = own_call || dialog_.empty() ? PreEstablishedReasonCode::Accepted : PreEstablishedReasonCode::Busy;
    // TS 24.380 clause 9.2.2.3.2 with TS 24.379 clause 11.1.1.2.1.2 step 4: a private call offered by Connect with
    // end-to-end security brings the caller's PCK in the PCK I_MESSAGE field (TS 24.380 clause 8.3.3.10); one that
    // fails authentication is refused, 'MIKEY-SAKKE authentication failed' (TS 24.380 table 8.3.3.6-1).
    std::optional<keying::KeyMessage> key;
    if (!own_call && answer == PreEstablishedReasonCode::Accepted &&
        message.session_type == PreEstablishedSessionType::Private && protected_media()) {
      try {
        if (message.pck_i_message.empty()) throw std::runtime_error("private call Connect without a PCK I_MESSAGE");
        key = accept_private_key(key_mgmt_of(message.pck_i_message), message.inviting_user);
      } catch (const std::exception &error) {
        event("private_call_key_rejected", {{"calling_user", message.inviting_user}, {"reason", error.what()}});
        answer = PreEstablishedReasonCode::MikeyAuthenticationFailed;
      }
    }
    const auto outcome = control.receive_connect(message, answer);
    if (outcome.acknowledge) pre_session_send(*outcome.acknowledge);
    if (own_call || outcome.call_started) {
      pre_session_connected(message);
      if (key) bind_private_key(key, false);
    }
  } else if (message.type == PreEstablishedMessageType::Disconnect) {
    const auto outcome = control.receive_disconnect(message);
    if (outcome.acknowledge) pre_session_send(*outcome.acknowledge);
    // TS 24.380 clause 9.2.2.3.4: a Disconnect in place of the Connect: the call over the session failed.
    if (call_over_pre_session() && status_.value("call_state", "") == "calling") report_followup(false);
    if (call_over_pre_session()) call_state(dialog_, SipCallState::disconnected, 200);
  }
  pre_session_status();
}

//------------------------------------------------------------------------------
// TS 24.380 clause 9.2.2.3.2 steps 1 b to d (and clause 4.1.2.2 for the originator): the call over the session is
// established: its session identity (the MCPTT Session Identity field), type and group, the session's media, and
// the floor participant created; the client then reports the call as for a confirmed dialog (the implicit floor
// request's outcome, the conference subscription).
void Client::pre_session_connected(const PreEstablishedMessage &connect) {
  if (call_over_pre_session()) {
    // The call this client set up over the session (TS 24.380 clause 9.2.2.3.5), or answered in it (clause
    // 9.2.2.3.6): its media are the session's, an implicit floor request of its REFER answered by the Connect.
    remote_ = pre_session_->remote;
    remote_->implicit_request = std::exchange(pre_session_implicit_, false);
    remote_->granted = false;
    if (local_.control == MediaControl::none) remote_->control = MediaControl::none;
    call_answered_ = !remote_->implicit_request;
    // TS 24.380 clause 9.3.2.4.10 step 1 g: a first-to-answer call's Connect names the user who answered.
    if (first_to_answer_ && !connect.invited_user.empty()) {
      private_peer_ = connect.invited_user;
      event("first_to_answer_answered", {{"called_party", private_peer_}, {"pre_established", true}});
    }
  } else {
    // A call offered to this client over the session (TS 24.379 clause 10.1.1.2.2.2 by Connect, automatic
    // commencement): it takes the session's media.
    dialog_ = "pre-call-" + random_id();
    remote_ = pre_session_->remote;
    remote_->implicit_request = remote_->granted = false;
    local_.floor_ssrc = pre_session_->local.floor_ssrc;
    floor_sequence_.reset();
    floor_timers_ = participant_timers();
    call_answered_ = true;
    call_type_ = connect.session_type == PreEstablishedSessionType::Chat    ? "chat"
                 : connect.session_type == PreEstablishedSessionType::Private ? "private"
                                                                              : "prearranged";
    call_group_ = connect.group_identity;
    if (call_type_ == "private") {
      private_peer_ = connect.inviting_user;
      first_to_answer_ = false;
      status_["calling_user"] = connect.inviting_user;
    }
    // TS 24.380 clause 8.3.3.2: Control Channel 0, the call has no floor control (a private call without floor
    // control, TS 24.379 clause 11.1.3.1.2.2): the speech stream only.
    const bool no_control = connect.media_streams && connect.media_streams->control_channel == 0;
    local_.control = no_control ? MediaControl::none : MediaControl::floor;
    if (no_control) {
      remote_->control = MediaControl::none;
      local_.audio_ssrc = pre_session_->local.audio_ssrc;
    }
    status_["call_type"] = call_type_;
    status_["call_group"] = call_group_;
    status_["inviting_user"] = connect.inviting_user;
  }
  session_uri_ = connect.session_identity;
  status_["session_uri"] = session_uri_;
  // TS 24.379 clause 11.1.7.2.2 ("upon completion of the procedures of clause 11.1.1.2.2.1"): the outcome of a
  // remotely initiated, transferred or forwarded private call set up over the session (report_followup).
  if (followup_ && call_type_ == "private" && followup_->target == private_peer_) report_followup(true);
  event("pre_session_call_connected",
        {{"session_identity", connect.session_identity}, {"group", connect.group_identity}});
  call_state(dialog_, SipCallState::confirmed, 200);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.2.2 with clause 10.1.1.2.1.2 ("the reader is assumed to replace occurrences of SIP INVITE
// request with SIP re-INVITE request"): a call offered in the session's dialog: the user is told and answers ("answer"
// or "reject", TS 24.379 clause 6.2.3.2: manual commencement, implied by a re-INVITE, NOTE 1 of TS 24.379 clause
// 6.3.2.2.6.3); the answer is the session's media. Busy with another call: 486 (clause 10.1.1.2.1.2). The Connect that
// follows the 200 (OK) gives the call its session identity (TS 24.380 clause 9.2.2.4.2, NOTE 4 of clause 6.3.2.2.6.3).
SipResponse Client::pre_session_call_offer(const SipMessage &message) {
  SipResponse response;
  if (!dialog_.empty() || !message.remote_media) {
    response.status_code = dialog_.empty() ? 488 : 486;
    return response;
  }
  const auto info = message_info(message, mcptt_service);
  const bool private_call = info.session_type == "private";
  // TS 24.379 clause 11.1.1.2.2.2 with clause 11.1.1.2.1.2 step 4: a private call's offer carries the caller's PCK.
  std::optional<keying::KeyMessage> key;
  if (private_call && protected_media()) {
    try {
      if (message.remote_media->key_mgmt.empty()) throw std::runtime_error("private call offer without a PCK");
      key = accept_private_key(message.remote_media->key_mgmt, info.calling_user);
    } catch (const std::exception &error) {
      event("private_call_key_rejected", {{"calling_user", info.calling_user}, {"reason", error.what()}});
      response.status_code = 488;
      response.headers = {{"Warning", mikey_failed}};
      return response;
    }
  }
  dialog_ = "pre-call-" + random_id();
  local_.floor_ssrc = pre_session_->local.floor_ssrc;
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  call_type_ = private_call ? "private"
               : info.session_type == "chat" ? "chat" : info.broadcast == true ? "broadcast" : "prearranged";
  call_group_ = private_call || info.calling_group == group() ? std::string{} : info.calling_group;
  auto answer = pre_session_->local;
  answer.floor_priority = message.remote_media->floor_priority;
  // TS 24.379 clause 11.1.3.1.2.2: an offer without a media-floor control section, a call without floor control.
  local_.control = message.remote_media->control;
  if (message.remote_media->control == MediaControl::none) {
    answer.control = MediaControl::none;
    answer.control_removed = message.remote_media->control_removed; // RFC 3264 section 6: one m-line per offered
    if (answer.ice) answer.ice->control.clear();
    local_.audio_ssrc = answer.audio_ssrc;
  }
  if (private_call) {
    private_peer_ = info.calling_user;
    first_to_answer_ = false;
    bind_private_key(key, false);
  }
  SipResponse accepted;
  accepted.status_code = 200;
  accepted.local_media = answer;
  pending_answer_ = accepted;
  pre_session_reinvite_ = message.transaction_id;
  status_["call_state"] = "ringing";
  status_["calling_user"] = info.calling_user;
  status_["call_type"] = call_type_;
  if (private_call) {
    // TS 24.379 clause 11.1.1.2.1.2 step 7 (TS 37.579-2 table 6.2.11.3.2-1 step 1): the user is alerted, 180
    // (Ringing), then the user's answer.
    event("private_call_incoming",
          {{"calling_user", info.calling_user}, {"commencement", "manual"}, {"pre_established", true}});
    response.deferred = true;
    schedule([this, transaction = message.transaction_id] {
      if (pre_session_reinvite_ != transaction) return;
      SipResponse ringing;
      ringing.status_code = 180;
      ringing.reason = "Ringing";
      sip_.respond(transaction, ringing);
    });
    return response;
  }
  event("group_call_incoming", {{"group", info.calling_group},
                                {"calling_user", info.calling_user},
                                {"commencement", "manual"},
                                {"pre_established", true}});
  response.deferred = true; // TS 37.579-1 table 5.3.5.3-1 step 3a1: 100 (Trying), then the user's answer
  return response;
}

} // namespace mcx
