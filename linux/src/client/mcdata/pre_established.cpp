//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The MCData client's pre-established session (TS 24.282 clause 18.3): establishment by an INVITE to the
// participating MCData function (clause 18.3.2.1), release by either side (clauses 18.3.3.1.1 and 18.3.3.1.2),
// modification by the participating function (clause 18.3.4.1.2); and the SDS communications over it (clause 9.2.5):
// the REFER that starts a one-to-one or group standalone SDS over the media plane or SDS session (clauses
// 9.2.5.2.1.1 and 9.2.5.3.1.1), the participating function's re-INVITE that reports its outcome or offers a
// communication (clauses 9.2.5.2.1.2 and 9.2.5.3.1.2), and its release by a REFER "BYE" or the participating
// function's re-INVITE (clauses 9.2.5.4.1.1 and 9.2.5.4.1.2). The media plane is that of TS 24.582 clause 6.1
// (clause 12.1): the client connects, as the active MSRP endpoint, to the session's negotiated MSRP URI.
//
// Not implemented: ICE connectivity checks (the offer carries the host candidate of the MSRP listener, RFC 8839; a
// participating function that answers without ICE attributes turns ICE off, RFC 8839 section 4.2.3), the emergency
// and imminent peril communications over the session (clauses 9.2.5.2.1.3 to 9.2.5.2.1.5), and a participating
// function that answers "a=setup:active" (the session is then not used: communications go on demand).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.582 V19.0.0 (Rel-19), RFC 3515, RFC 4488, RFC 4538, RFC 2392,
// RFC 5366, RFC 4975, RFC 4145, RFC 8839, RFC 3261.

#include "client.hpp"
#include "mcx/refer.hpp"

namespace mcx {

namespace {

//------------------------------------------------------------------------------
// TS 24.282 clauses 9.2.5.2.1.1 step 9 and 9.2.5.3.1.1 step 8 (REFER), clause 18.3.2.1 step 3 (INVITE): the SDS media
// feature tag (and ICSI) of the Contact, on the engine's own contact target.
std::string sds_contact(const std::string &target) {
  return "<" + target + ">;+g.3gpp.mcdata.sds;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds\"";
}

//------------------------------------------------------------------------------
// The MCData-Info part of a SIP message, as received (the incoming protection filter decrypted it): from the parts or a
// single body of that type; empty when there is none.
std::string info_body_of(const SipMessage &message) {
  for (const auto &part : message.parts)
    if (part.content_type == mcdata::info_mime) return part.body;
  if (message.content_type.substr(0, message.content_type.find(';')) == mcdata::info_mime) return message.body;
  return {};
}

//------------------------------------------------------------------------------
// Whether the SIP message carries an SDP body (an offer, in a re-INVITE or UPDATE).
bool has_sdp(const SipMessage &message) {
  for (const auto &part : message.parts)
    if (part.content_type.starts_with("application/sdp")) return true;
  return message.content_type.starts_with("application/sdp");
}

} // namespace

//------------------------------------------------------------------------------
// TS 24.282 clause 18.3.1.1 with TS 24.582 clause 12.1: the session's MSRP media in an offer of a communication over it
// (the REFER's SDP, clauses 9.2.3.2.1 and 9.2.4.2.1): its MSRP URI, "a=setup:actpass", the direction asked.
MsrpMedia Client::mcdata_pre_media(const std::string &direction) const {
  auto media = mcdata_pre_->local;
  media.direction = direction;
  media.setup = "actpass";
  return media;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 18.3.1.1 (an offer, "actpass") or clause 18.3.1.2 (an answer, "active": this client connects): the
// session's SDP, with the ICE attributes of the offer (clause 18.3.2.1 step 10, RFC 8839).
std::string Client::mcdata_pre_sdp(const std::string &setup) const {
  auto media = mcdata_pre_->local;
  media.setup = setup;
  return msrp_sdp(media, 1) + client_rules::msrp_ice_attributes(mcdata_pre_->ice_ufrag, mcdata_pre_->ice_password,
                                                                media.address, media.port);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 18.3.2.1: "mcdata-pre-establish": ICE candidates gathered on the MCData interface (step 1: the MSRP
// listener's host candidate); an initial INVITE (step 2) to the participating function's PSI (1)), with the SDS media
// feature tag in Contact (3)), the two Accept-Contact header fields (4), 6)), the SDS ICSI in P-Preferred-Service
// (5)); the engine adds Supported "timer" and Session-Expires without a refresher (7), 8)); an MCData-Info body with
// <pre-established-session-ind> "true" in <anyExt> (9)) and the SDP offer of clause 18.3.1.1 (10)): "m=message",
// "a=sendrecv", its MSRP URI, the accepted types, "a=setup:actpass".
void Client::mcdata_pre_establish() {
  if (!mcdata_authorized_) throw std::runtime_error("a pre-established session requires MCData service authorisation");
  if (mcdata_pre_) throw std::runtime_error("an MCData pre-established session already exists");
  auto pre = std::make_unique<McdataPreSession>();
  const auto address = resolve_ipv4(cfg_.at("media").at("advertise"));
  pre->listener = std::make_unique<msrp::Endpoint>();
  // A participating function answering "active" would connect here; the communications then go on demand
  // (mcdata_pre_usable), so what arrives is only read.
  const auto local = pre->listener->listen(
      address,
      {[this](const msrp::Message &message) { mcdata_media_incoming(message); },
       [this](std::string_view reason) { event("mcdata_pre_session_msrp_failed", {{"reason", std::string(reason)}}); },
       {}});
  pre->local.address = address;
  pre->local.port = local.port;
  pre->local.path = local.text();
  pre->local.setup = "actpass";                                              // TS 24.282 clause 18.3.1.1 1)f)
  pre->local.direction = "sendrecv";                                         // clause 18.3.1.1 1)c)
  pre->local.accept_types = {mcdata::signalling_mime, mcdata::payload_mime}; // clause 18.3.1.1 1)e)
  pre->ice_ufrag = random_id().substr(0, 8);
  pre->ice_password = random_id().substr(0, 24); // RFC 8839 section 5.4: at least 22 characters
  mcdata_pre_ = std::move(pre);
  const auto info = client_rules::mcdata_info_with_any_ext(
      mcdata::info_xml(mcdata::Info{}), "<pre-established-session-ind>true</pre-established-session-ind>");
  auto headers = mcdata_media_headers();
  for (auto &header : headers)
    if (header.name == "Contact") header.value = sds_contact(sip_.contact_target()); // clause 18.3.2.1 step 3
  try {
    mcdata_pre_->dialog = sip_.invite_sdp(
        mcdata_service(), mcdata_pre_sdp("actpass"), mcdata_seal_signalling({{mcdata::info_mime, info}}),
        [this](const SipResponse &raw) { mcdata_pre_answered(raw); }, headers);
  } catch (...) {
    mcdata_pre_.reset();
    throw;
  }
  event("mcdata_pre_session_requested", {{"dialog", mcdata_pre_->dialog}});
  mcdata_pre_status();
}

//------------------------------------------------------------------------------
// TS 24.282 clause 18.3.2.1 ("Upon receiving a SIP 2xx response ... shall interact with the media plane", TS 24.582
// clause 12.1): the URI identifying the session (the Contact of the 2xx, clause 18.3.2.2 step 9 a) and the negotiated
// MSRP media; a failure ends the attempt.
void Client::mcdata_pre_answered(const SipResponse &raw) {
  const auto response = mcdata_opened_response(raw);
  if (response.status_code < 200 || !mcdata_pre_) return;
  if (response.status_code >= 300) {
    event("mcdata_pre_session_failed",
          {{"status", response.status_code}, {"warning", std::string(get_header(response.headers, "Warning"))}});
    mcdata_pre_.reset();
    mcdata_pre_status();
    return;
  }
  mcdata_pre_->uri = contact_uri_of(get_header(raw.headers, "Contact"));
  for (const auto &part : response.parts)
    if (part.content_type.starts_with("application/sdp")) mcdata_pre_->remote = parse_msrp_sdp(part.body);
  if (!mcdata_pre_->remote && response.content_type.starts_with("application/sdp"))
    mcdata_pre_->remote = parse_msrp_sdp(response.body);
  mcdata_pre_->state = "established";
  event("mcdata_pre_session_established",
        {{"session_uri", mcdata_pre_->uri},
         {"setup", mcdata_pre_->remote ? Json(mcdata_pre_->remote->setup) : Json(nullptr)}});
  mcdata_pre_status();
}

//------------------------------------------------------------------------------
// TS 24.282 clause 18.3.3.1.1: "mcdata-pre-release": the procedure of clause 13.2.2.2.2.1, a BYE (in the session's
// dialog, to its URI); its end releases the media plane resources (mcdata_pre_state).
void Client::mcdata_pre_release() {
  if (!mcdata_pre_) throw std::runtime_error("no MCData pre-established session to release");
  if (!mcdata_pre_->communication.empty()) mcdata_pre_drop_media();
  mcdata_pre_->state = "releasing";
  sip_.bye(mcdata_pre_->dialog);
  mcdata_pre_status();
}

//------------------------------------------------------------------------------
// Whether a communication can start over the pre-established session: established, not in use, and answered
// "passive" (this client connects to the participating function's MSRP URI, TS 24.582 clause 6.1.1.2.1).
bool Client::mcdata_pre_usable() const {
  return mcdata_pre_ && mcdata_pre_->state == "established" && mcdata_pre_->communication.empty() &&
         mcdata_pre_->remote && mcdata_pre_->remote->setup == "passive" && !mcdata_media_;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.5.2.1.1 (one-to-one) and clause 9.2.5.3.1.1 (group): the SIP REFER outside a dialog that starts
// a standalone SDS over the media plane or an SDS session within the pre-established session: the Request-URI the
// session's identity (1)); the Refer-To a cid URL of the resource list (2)), with its Content-ID; the list's single
// <entry> the called user or group (4) / 3)), whose hname "body" holds the SDP offer of clause 9.2.3.2.1 or 9.2.4.2.1
// (the session's MSRP media, a PCK I_MESSAGE for a one-to-one communication, clause 9.2.5.2.1.1 3)) and the
// MCData-Info (the <request-type>, <mcdata-client-id>, for a group <mcdata-request-uri>, the functional alias, the
// application priority); P-Preferred-Service the SDS ICSI (5) / 4)); Supported "norefersub" and Refer-Sub "false"
// (7) / 6), RFC 4488); Target-Dialog the session (8) / 7), RFC 4538); the SDS media feature tag in Contact (9) / 8)).
// Require "multiple-refer": RFC 5368 section 4 for a Refer-To naming a URI list. The XML bodies are confidentiality
// protected (TS 24.282 clause 6.5.2.3.1): the MCData-Info inside the entry, then the entry's "uri" with the list.
void Client::mcdata_pre_refer(const std::string &target, const mcdata::Info &info, const MsrpMedia &offer) {
  auto &pre = *mcdata_pre_;
  const auto ids = sip_.dialog_identifiers(pre.dialog);
  if (!ids || pre.uri.empty()) throw std::runtime_error("the pre-established session has no dialog identifiers");
  auto entry_body =
      mcdata_seal_signalling({{"application/sdp", msrp_sdp(offer, 1), random_id().substr(0, 16) + "@mcx"},
                              {mcdata::info_mime, mcdata::info_xml(info), random_id().substr(0, 16) + "@mcx"}});
  const ReferEntry entry{target, {}, std::move(entry_body)};
  const auto content_id = random_id().substr(0, 16) + "@mcx";
  auto list = mcdata_encrypt_xml({refer_resource_list_part(entry, content_id, "mcdata-entry")}, mcdata_xml_key());
  const std::vector<SipHeader> headers{{"Refer-To", cid_refer_to(content_id)},
                                       {"Refer-Sub", "false"},
                                       {"Supported", "norefersub"},
                                       {"Require", "multiple-refer"},
                                       {"Target-Dialog", target_dialog_value(*ids)},
                                       {"P-Preferred-Service", mcdata::sds_icsi},
                                       {"Contact", sds_contact(sip_.contact_target())}};
  pre.communication = "requested";
  pre.last_state.clear();
  mcdata_media_pre_ = true;
  sip_.refer(pre.uri, list, headers, [this](const SipResponse &raw) {
    const auto response = mcdata_opened_response(raw);
    if (response.status_code < 200 || !mcdata_pre_) return;
    if (response.status_code < 300) {
      // TS 24.282 clause 9.2.5.2.1.1, final 2xx: the communication's outcome comes in the re-INVITE; 1) the
      // application priority the server applied.
      for (const auto &part : response.parts)
        if (part.content_type == mcdata::info_mime)
          if (const auto used = mcdata::parse_info(part.body).used_priority)
            mcdata_media_result_["used_priority"] = *used;
      event("mcdata_pre_refer_accepted", {{"status", response.status_code}});
      mcdata_pre_status();
      return;
    }
    mcdata_media_result_["status"] = response.status_code;
    mcdata_media_result_["warning"] = std::string(get_header(response.headers, "Warning"));
    mcdata_media_result_["state"] = "rejected";
    mcdata_pre_drop_media();
    failure("communication over the pre-established session: " + std::to_string(response.status_code));
  });
  mcdata_media_result_["pre_established"] = true;
  event("mcdata_pre_refer_sent", {{"target", target}, {"request_type", info.request_type}});
  mcdata_pre_status();
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.5.4.1.1: the user leaves the MCData communication within the pre-established session (or a
// standalone transfer ends, TS 24.582 clause 6.1.1.2.1): 1) the media plane released (TS 24.582 clause 8: the MSRP
// session and its connection, mcdata_media_close); 2)-9) a REFER outside a dialog to the session's identity (3)),
// Refer-Sub "false" (4)), Supported "norefersub" (5)), the Refer-To the MCData session identity (6)) with "method"
// "BYE" (7)), Target-Dialog the session (8)). The participating function's re-INVITE "terminated" completes it.
void Client::mcdata_pre_leave() {
  if (!mcdata_pre_) return;
  auto &pre = *mcdata_pre_;
  const auto ids = sip_.dialog_identifiers(pre.dialog);
  if (!ids) return;
  const auto session = pre.communication_uri.empty() ? pre.uri : pre.communication_uri;
  const std::vector<SipHeader> headers{{"Refer-To", bye_refer_to(session)},
                                       {"Refer-Sub", "false"},
                                       {"Supported", "norefersub"},
                                       {"Target-Dialog", target_dialog_value(*ids)},
                                       {"P-Preferred-Service", mcdata::sds_icsi},
                                       {"Contact", sds_contact(sip_.contact_target())}};
  pre.communication = "releasing";
  sip_.refer(pre.uri, {}, headers, [this](const SipResponse &response) {
    if (response.status_code < 200 || !mcdata_pre_) return;
    if (response.status_code >= 300) {
      // The participating function refused the release: the communication is over on this side all the same.
      failure("release within the pre-established session: " + std::to_string(response.status_code));
      mcdata_pre_->communication.clear();
    }
    event("mcdata_pre_leave_answered", {{"status", response.status_code}});
    mcdata_pre_status();
  });
  event("mcdata_pre_leave_sent", {{"session", session}});
  mcdata_pre_status();
}

//------------------------------------------------------------------------------
// TS 24.582 clauses 12.1 and 6.1.1.2.1 / 6.1.2.2.1: the communication is established: this client, the active
// endpoint, connects to the participating function's MSRP URI of the session's SDP answer, from the session's own MSRP
// URI, and binds the connection with an empty SEND; the pending SDS then goes (mcdata_media_tick).
void Client::mcdata_pre_connect() {
  if (!mcdata_pre_ || !mcdata_pre_->remote) return;
  const auto peer = msrp::parse_uri(mcdata_pre_->remote->path);
  const auto local = msrp::parse_uri(mcdata_pre_->local.path);
  mcdata_media_ = std::make_unique<msrp::Endpoint>();
  mcdata_media_role_ = "active";
  if (mcdata_media_result_.is_object()) mcdata_media_result_["state"] = mcdata_media_session_ ? "open" : "connecting";
  mcdata_media_->connect(
      peer, local,
      {[this](const msrp::Message &message) { mcdata_media_incoming(message); },
       [this](std::string_view reason) { event("mcdata_media_failed", {{"reason", std::string(reason)}}); },
       [this] { mcdata_media_acknowledged_ = true; }});
}

//------------------------------------------------------------------------------
// The communication over the pre-established session ends on this side without signalling of its own (the
// participating function released it, or the session ended): its media plane resources released (TS 24.282 clause
// 9.2.5.4.1.2 1)ii), TS 24.582 clause 8); the session stays.
void Client::mcdata_pre_drop_media() {
  mcdata_media_dialog_.clear(); // no BYE and no REFER "BYE" from mcdata_media_close
  mcdata_media_pre_ = false;
  mcdata_media_close();
  if (mcdata_pre_) {
    mcdata_pre_->communication.clear();
    mcdata_pre_->communication_uri.clear();
  }
}

//------------------------------------------------------------------------------
// Requests in the pre-established session's dialog: re-INVITE (clauses 9.2.5.2.1.1, 9.2.5.3.1.1, 9.2.5.2.1.2,
// 9.2.5.3.1.2, 9.2.5.4.1.1, 9.2.5.4.1.2, 18.3.4.1.2), UPDATE (clause 18.3.4.1.2) and BYE (clause 18.3.3.1.2: 1) an
// MCData session using it is released, TS 24.582 clause 8; 2) none: clause 13.2.3.2.2, the 200 (OK) and the reason
// shown). Empty for other dialogs.
std::optional<SipResponse> Client::mcdata_pre_request(const SipMessage &message) {
  if (!mcdata_pre_ || message.dialog_id != mcdata_pre_->dialog) return std::nullopt;
  SipResponse response;
  response.status_code = 200;
  if (message.method == "BYE") {
    if (!mcdata_pre_->communication.empty()) {
      mcdata_media_result_["state"] = "released";
      mcdata_pre_drop_media();
      ++mcdata_pre_->terminated;
    }
    if (const auto reason = get_header(message.headers, "Reason"); !reason.empty() && mcdata_media_result_.is_object())
      mcdata_media_result_["release_reason"] = std::string(reason); // TS 24.282 clause 13.2.3.2.2 1)
    event("mcdata_pre_session_released_by_server", {{"reason", std::string(get_header(message.headers, "Reason"))}});
    return response;
  }
  if (message.method == "INVITE") return mcdata_pre_reinvite(message);
  if (message.method == "UPDATE") {
    // TS 24.282 clause 18.3.4.1.2: 1) an acceptable "m=message" offer, else 488; 2)-3) the 200 (OK) with the answer.
    if (!has_sdp(message)) return response;
    response.local_sdp = mcdata_pre_sdp("active");
    event("mcdata_pre_session_modified", {{"method", "UPDATE"}});
    return response;
  }
  response.status_code = 405;
  return response;
}

//------------------------------------------------------------------------------
// The participating function's re-INVITE within the pre-established session, by its <mcdata-communication-state>:
//   establish-success / establish-fail  the outcome of this client's REFER (clauses 9.2.5.2.1.1, 9.2.5.3.1.1: the user
//                                       notified; on success the media plane, TS 24.582 clause 12.1; on failure the
//                                       state restored)
//   terminated                          the end of the communication this client left (clause 9.2.5.4.1.1)
//   establish-request                   a communication offered (clauses 9.2.5.2.1.2, 9.2.5.3.1.2): the procedures of
//                                       clause 9.2.3.2.4 (standalone) or 9.2.4.2.4 (session), mcdata_media_invite
//   terminate-request                   the communication released by the server (clause 9.2.5.4.1.2): 200 (OK), the
//                                       media plane resources released
//   none                                a modification of the session (clause 18.3.4.1.2)
// A re-INVITE without an SDP offer is answered with one in the 2xx (RFC 3261 section 13.3.1: "If the INVITE does not
// contain an offer, the UAS MUST include an offer in the 2xx"), the session's media as negotiated ("actpass"); with an
// offer, the answer is "active" (this client connects).
SipResponse Client::mcdata_pre_reinvite(const SipMessage &message) {
  auto &pre = *mcdata_pre_;
  SipResponse response;
  response.status_code = 200;
  const auto info_xml = info_body_of(message);
  const auto state = client_rules::mcdata_communication_state(info_xml);
  const bool offer = has_sdp(message);
  response.local_sdp = mcdata_pre_sdp(offer ? "active" : "actpass");
  if (!state.empty()) pre.last_state = state;
  if (state == "establish-request") {
    if (!pre.communication.empty()) {
      // TS 24.282 clause 9.2.5.2.1.2 1): the session has an MCData communication already (clause 6.2.8.4.2 deals
      // with its priority, not with a second communication).
      response.status_code = 486;
      response.local_sdp.reset();
      return response;
    }
    auto answer = mcdata_media_invite(message);
    if (answer.status_code / 100 == 2) {
      pre.communication = "established";
      pre.communication_uri = contact_uri_of(get_header(message.headers, "Contact"));
      ++pre.established;
      mcdata_media_pre_ = true;
      event("mcdata_pre_communication_offered", {{"session", pre.communication_uri}});
    }
    mcdata_pre_status();
    return answer;
  }
  if (state == "terminate-request") {
    // TS 24.282 clause 9.2.5.4.1.2: i) the 200 (OK); ii) the media plane resources of the communication released.
    if (!pre.communication.empty()) {
      mcdata_media_result_["state"] = "released";
      mcdata_pre_drop_media();
      ++pre.terminated;
    }
    event("mcdata_pre_communication_released", {{"by", "server"}});
    mcdata_pre_status();
    return response;
  }
  if (state == "establish-success" && pre.communication == "requested") {
    // TS 24.282 clause 9.2.5.2.1.1 1)i) / 9.2.5.3.1.1 1)i): the user is notified of the successful establishment;
    // 5) / 4) the media plane (TS 24.582 clause 12.1).
    pre.communication = "established";
    const auto contact = std::string(get_header(message.headers, "Contact"));
    // The MCData session identity of the communication: the Contact naming a focus (as the participating function
    // names it to a terminating client, clause 9.2.5.2.2.2 3)iii)); otherwise the session's own identity.
    pre.communication_uri = contact.find("isfocus") != std::string::npos ? contact_uri_of(contact) : pre.uri;
    if (const auto used = mcdata::parse_info(info_xml).used_priority) mcdata_media_result_["used_priority"] = *used;
    ++pre.established;
    mcdata_media_dialog_ = pre.dialog;
    schedule([this] { mcdata_pre_connect(); });
    event("mcdata_pre_communication_established", {{"session", pre.communication_uri}});
    mcdata_pre_status();
    return response;
  }
  if (state == "establish-fail" && pre.communication == "requested") {
    // TS 24.282 clause 9.2.5.2.1.1 2)i): the user notified of the failure, the state variables restored.
    mcdata_media_result_["state"] = "rejected";
    mcdata_pre_drop_media();
    event("mcdata_pre_communication_failed", {});
    mcdata_pre_status();
    return response;
  }
  if (state == "terminated") {
    // TS 24.282 clause 9.2.5.4.1.1 1)i): the user is notified of the successful termination.
    if (pre.communication == "releasing" || pre.communication == "established") {
      if (pre.communication == "established") mcdata_pre_drop_media();
      pre.communication.clear();
      pre.communication_uri.clear();
      ++pre.terminated;
    }
    event("mcdata_pre_communication_terminated", {});
    mcdata_pre_status();
    return response;
  }
  // TS 24.282 clause 18.3.4.1.2 (no communication) or a re-INVITE of the communication in use (its priority, clause
  // 6.2.8.4.2): the 200 (OK) with the session's media.
  if (!pre.communication.empty() && !info_xml.empty()) {
    auto answer = mcdata_media_reinvite(mcdata::parse_info(info_xml));
    answer.local_sdp = response.local_sdp;
    return answer;
  }
  event("mcdata_pre_session_modified", {{"method", "INVITE"}, {"offer", offer}});
  return response;
}

//------------------------------------------------------------------------------
// The pre-established session's dialog: its end (the client's BYE answered, the participating function's BYE, the
// SIP core's) releases the session and any communication over it (TS 24.282 clauses 18.3.3.1.1 and 18.3.3.1.2, TS
// 24.582 clause 8). False for other dialogs.
bool Client::mcdata_pre_state(std::string_view dialog, SipCallState state) {
  if (!mcdata_pre_ || dialog != mcdata_pre_->dialog) return false;
  if (state == SipCallState::confirmed && mcdata_pre_->state == "establishing") {
    mcdata_pre_->state = "established";
  } else if (state == SipCallState::disconnected) {
    if (!mcdata_pre_->communication.empty()) mcdata_pre_drop_media();
    event("mcdata_pre_session_released", {{"dialog", mcdata_pre_->dialog}});
    if (mcdata_pre_->listener) mcdata_pre_->listener->close();
    mcdata_pre_.reset();
  }
  mcdata_pre_status();
  return true;
}

//------------------------------------------------------------------------------
// The pre-established session in status.json ("mcdata_pre_session"): its state and identity, the communication over
// it and the counts of communications established and terminated (the user's notifications, TS 24.282 clauses
// 9.2.5.2.1.1 and 9.2.5.4.1.1).
void Client::mcdata_pre_status() {
  if (!mcdata_pre_) {
    status_["mcdata_pre_session"] = nullptr;
    return;
  }
  const auto &pre = *mcdata_pre_;
  status_["mcdata_pre_session"] = {{"state", pre.state},
                                   {"uri", pre.uri},
                                   {"dialog", pre.dialog},
                                   {"communication", pre.communication},
                                   {"communication_uri", pre.communication_uri},
                                   {"last_state", pre.last_state},
                                   {"established", pre.established},
                                   {"terminated", pre.terminated},
                                   {"setup", pre.remote ? Json(pre.remote->setup) : Json(nullptr)}};
}

} // namespace mcx
