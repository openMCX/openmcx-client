//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData communications over the media plane, terminating MCData client side: incoming standalone SDS, SDS session
// and FD invitations, received MSRP content, and the MSRP transfer loop (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.582 V19.0.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// RFC 4975 (MSRP), RFC 4145 (SDP setup attribute), RFC 5547 (file transfer), RFC 3261 (SIP).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.282 clauses 9.2.3.2.4, 9.2.4.2.4, 10.2.5.2.4 and 24.2.2.1.2: an incoming standalone SDS, SDS session or FD
// over the media plane (an IP connectivity INVITE goes to TS 24.282 clause 20.2.2). The server offers the MSRP stream,
// so this client answers "active" and connects to it.
SipResponse Client::mcdata_media_invite(const SipMessage &invite) {
  SipResponse response;
  response.status_code = 200;
  const BodyPart *sdp = nullptr, *info_part = nullptr;
  for (const auto &part : invite.parts) {
    if (part.content_type.starts_with("application/sdp"))
      sdp = &part;
    else if (part.content_type == mcdata::info_mime)
      info_part = &part;
  }
  if (!sdp || !info_part) {
    response.status_code = 488;
    return response;
  }
  const auto service = get_header(invite.headers, "P-Asserted-Service");
  if (service == mcdata::ipconn_icsi || (!ipcon_dialog_.empty() && invite.dialog_id == ipcon_dialog_)) {
    // TS 24.282 clause 20.2.2: IP connectivity has its own media plane and state.
    const auto info = mcdata::parse_info(info_part->body);
    if (asserted_identity(invite) != mcdata_service() ||
        (invite.dialog_id != ipcon_dialog_ && (info.request_uri != mcdata_user() || info.calling_user.empty()))) {
      response.status_code = 403;
      return response;
    }
    return ipcon_incoming(invite, info, *sdp);
  }
  // TS 24.282 clauses 6.2.7.4 and 6.2.8.4.2: a re-INVITE in the dialog of the open session; the controlling function's
  // re-INVITE (clauses 6.3.7.1.1, 6.3.7.1.13, 6.3.7.1.21) carries no P-Asserted-Service and no <mcdata-request-uri>,
  // the dialog identifying the session.
  if (mcdata_media_ && invite.dialog_id == mcdata_media_dialog_)
    return mcdata_media_reinvite(mcdata::parse_info(info_part->body));
  const bool file_transfer = service == mcdata::fd_icsi; // TS 24.282 clause 10.2.5.2.4
  if (asserted_identity(invite) != mcdata_service() || (service != mcdata::sds_icsi && !file_transfer)) {
    response.status_code = 403;
    return response;
  }
  const auto info = mcdata::parse_info(info_part->body);
  if (info.request_uri != mcdata_user() || info.calling_user.empty()) {
    response.status_code = 403;
    return response;
  }
  if (mcdata_media_ && invite.dialog_id != mcdata_media_dialog_) {
    response.status_code = 486;
    return response;
  }
  if (mcdata_media_ && invite.dialog_id == mcdata_media_dialog_) return mcdata_media_reinvite(info);
  MsrpMedia offer;
  try {
    offer = parse_msrp_sdp(sdp->body);
  } catch (const std::exception &error) {
    event("mcdata_media_sdp_rejected", {{"reason", error.what()}});
    response.status_code = 488;
    return response;
  }
  if (offer.setup == "active") {
    response.status_code = 488;
    return response;
  } // this client does not listen
  if (auto refused = mcdata_media_accept_key(offer, info)) return *refused;
  Json offered = nullptr;
  if (file_transfer) {
    if (auto refused = mcdata_media_file_offer(invite, offer, info, offered)) return *refused;
  }
  // TS 24.282 clause 24.2.2.1.2: an ad hoc group SDS session is offered "a=sendrecv" (TS 24.282 clause 9.2.4.2.1),
  // a standalone SDS to an ad hoc group "a=sendonly".
  const bool adhoc = info.request_type == "adhoc-group-sds-session" || info.request_type == "adhoc-group-fd-session";
  const bool adhoc_session = info.request_type == "adhoc-group-sds-session" && offer.direction == "sendrecv";
  mcdata_media_session_state(invite, info, adhoc_session);
  const auto address = resolve_ipv4(cfg_.at("media").at("advertise"));
  mcdata_media_ = std::make_unique<msrp::Endpoint>();
  mcdata_media_result_ = {{"from", info.calling_user},
                          {"group", info.calling_group},
                          {"state", "answering"},
                          {"file", file_transfer},
                          {"adhoc", adhoc},
                          {"session", mcdata_media_session_}};
  // The functional alias the user was reached through (as TS 24.282 clause 20.2.2 step 3B shows it).
  if (!info.called_functional_alias.empty()) mcdata_media_result_["called_alias"] = info.called_functional_alias;
  // TS 24.282 clauses 9.2.3.4.3 step c and 9.2.4.4.3 step d: the priority the controlling function applied.
  mcdata_media_result_["used_priority"] = info.used_priority ? Json(*info.used_priority) : Json(nullptr);
  mcdata_media_group_ = info.calling_group;
  mcdata_media_peer_ = info.calling_group.empty() ? info.calling_user : std::string{};
  mcdata_media_select_key(info, adhoc);
  mcdata_media_offered_ = offered;
  mcdata_media_answer_offer(response, invite, offer, address, file_transfer, adhoc_session);
  // TS 24.282 clause 9.2.3.2.4 steps 5, 7 and 8 (9.2.4.2.4 steps 6, 8 and 9 for a session; 10.2.5.2.4 6)b), d), e)
  // for a file): "timer" in Require, the service's media feature tag and ICSI in the Contact of the 200 (OK)
  response.headers.push_back({"Require", "timer"});
  response.contact_tags =
      file_transfer ? ";+g.3gpp.mcdata.fd;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.fd\""
                    : ";+g.3gpp.mcdata.sds;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds\"";
  event(file_transfer ? "mcdata_media_file_offered" : "mcdata_media_sds_offered",
        {{"from", info.calling_user}, {"group", info.calling_group}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.8.4.2 for a one-to-one session; clause 9.2.4.2.4 steps 4A and 4B for the
// in-progress emergency or imminent peril state of a group: a re-INVITE in the session's dialog.
SipResponse Client::mcdata_media_reinvite(const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  // TS 24.282 clauses 6.2.7.4 9) and 6.2.8.4.2 4): an SDP answer (clause 9.2.4.2.2) with the media as established.
  response.local_sdp = mcdata_media_current_sdp();
  if (!info.calling_group.empty()) {
    mcdata_group_priority_received(info);
    // TS 24.282 clause 6.2.7.4 7)-8): the g.3gpp.mcdata media feature tag and the MCData ICSI in the Contact.
    response.contact_tags = ";+g.3gpp.mcdata;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata\"";
    if (mcdata_media_result_.is_object()) {
      mcdata_media_result_["emergency"] = mcdata_session_emergency_;
      mcdata_media_result_["imminent"] = mcdata_session_imminent_;
    }
    event("mcdata_group_priority_received", {{"from", info.calling_user},
                                             {"group", info.calling_group},
                                             {"emergency", mdeg_ == 2},
                                             {"imminent", mdig_ == 2}});
    return response;
  }
  const bool emergency = info.emergency == true;
  mcdata_session_emergency_ = emergency;
  mdepc_ = emergency ? 3 : 1;
  if (emergency) mcdata_emergency_ = true;
  if (mcdata_media_result_.is_object()) mcdata_media_result_["emergency"] = emergency;
  event("mcdata_session_emergency_received", {{"from", info.calling_user}, {"emergency", emergency}});
  return response;
}

//------------------------------------------------------------------------------
// The SDP of the open media plane session as this client holds it: the answer it gave, or (a session it started)
// its media with the setup role the exchange gave it (RFC 4145 4.1: an answer is "active" or "passive").
std::string Client::mcdata_media_current_sdp() const {
  if (!mcdata_media_answer_.empty() || !mcdata_media_) return mcdata_media_answer_;
  MsrpMedia media;
  media.address = resolve_ipv4(cfg_.at("media").at("advertise"));
  media.port = mcdata_media_->port();
  media.path = mcdata_media_->local().text();
  media.setup = mcdata_media_role_.empty() ? "active" : mcdata_media_role_;
  media.direction = mcdata_media_session_ ? "sendrecv" : "sendonly";
  media.accept_types = {mcdata::signalling_mime, mcdata::payload_mime};
  return msrp_sdp(media, 1);
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 9.2.3.2.4 step 3, 9.2.4.2.4 step 3 and 10.2.5.2.4 step 3: the PCK offered with a one-to-one
// communication; 488 with warning 136 when the I_MESSAGE fails authentication. Empty when acceptable.
std::optional<SipResponse> Client::mcdata_media_accept_key(const MsrpMedia &offer, const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  if (!offer.key_mgmt.empty()) {
    if (!info.calling_group.empty()) {
      response.status_code = 488;
      return response;
    }
    try {
      mcdata_accept_pck(offer.key_mgmt, info.calling_user);
    } catch (const std::exception &error) {
      ++mcdata_protection_failures_;
      event("mcdata_pck_rejected", {{"from", info.calling_user}, {"reason", error.what()}});
      response.status_code = 488;
      response.headers = {{"Warning", "399 mcx \"136 authentication of the MIKEY-SAKKE I_MESSAGE failed\""}};
      return response;
    }
  }
  return std::nullopt;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.5.2.4 steps 5 to 7: the file metadata is in the SDP file attributes,
// and the FD SIGNALLING PAYLOAD says whether the download is mandatory. Fills the offered file; the refusal, or
// empty when the file is received.
std::optional<SipResponse> Client::mcdata_media_file_offer(const SipMessage &invite, const MsrpMedia &offer,
                                                           const mcdata::Info &info, Json &offered) {
  SipResponse response;
  response.status_code = 200;
  const BodyPart *signalling = nullptr;
  for (const auto &part : invite.parts)
    if (part.content_type == mcdata::signalling_mime) signalling = &part;
  if (!signalling) {
    response.status_code = 488;
    return response;
  }
  mcdata::FdSignalling request;
  try {
    request = mcdata::decode_fd_signalling(signalling->body);
  } catch (const std::exception &error) {
    event("mcdata_media_file_rejected", {{"reason", error.what()}});
    response.status_code = 488;
    return response;
  }
  const auto metadata = "file-selector:" + offer.file_selector;
  const auto size = mcdata::metadata_size(metadata).value_or(0);
  offered = {{"message_id", mcdata::uuid_text(request.message)},
             {"conversation_id", mcdata::uuid_text(request.conversation)},
             {"from", info.calling_user},
             {"group", info.calling_group},
             {"name", mcdata::metadata_name(metadata)},
             {"bytes", size},
             {"sha256", mcdata::metadata_sha256(metadata)},
             {"mandatory", request.mandatory_download},
             {"completion_requested", request.completion_requested},
             {"application_id", request.application_id ? Json(*request.application_id) : Json(nullptr)},
             // TS 24.282 clause 10.2.5.2.4: "may process the Application metadata container"
             {"application_metadata",
              request.application_metadata ? Json(*request.application_metadata) : Json(nullptr)}};
  // TS 24.282 clause 11.2 auto-receive, or an explicit mandatory download; otherwise the
  // invitation is declined, since these clients have no interactive user.
  const auto fd_cfg = cfg_.at("mcdata").value("fd", Json::object());
  const auto automatic = fd_cfg.value("auto_receive_bytes", 0u);
  if (!request.mandatory_download && !(automatic && size && size <= automatic) && mcdata_media_user_accepted_)
    return std::nullopt; // TS 24.282 clause 10.2.5.2.4 7)d): the user accepted
  if (!request.mandatory_download && !(automatic && size && size <= automatic) && fd_cfg.value("ask_user", false)) {
    // TS 24.282 clause 10.2.5.2.4 7)a): "shall notify the user ... and wait for the user to accept, decline or
    // defer"; the INVITE is answered 180 (Ringing) meanwhile and completed by mcdata_media_file_answer.
    response.status_code = 180;
    response.deferred = true; // SipEngine: the INVITE stays open for the final answer (SipEngine::answer)
    mcdata_media_pending_invite_ = invite;
    offered["state"] = "offered";
    bounded_push(mcdata_media_files_, offered);
    event("mcdata_media_file_offered_to_user", {{"from", info.calling_user}, {"bytes", size}});
    return response;
  }
  if (!request.mandatory_download && !(automatic && size && size <= automatic)) {
    response.status_code = 480;
    response.headers = {{"Warning", "399 mcdata \"110 user declined the call invitation\""}};
    offered["state"] = "declined";
    bounded_push(mcdata_media_files_, offered);
    event("mcdata_media_file_declined", {{"from", info.calling_user}, {"bytes", size}});
    return response;
  }
  return std::nullopt;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.5.2.4 7)b)-d): the user's decision on the pending FD invitation: declined, 480 with the Warning
// "110 user declined the call invitation"; deferred, 480 with "231 user deferred the call invitation"; accepted, the
// 200 (OK) of steps 7)d)i)-vii).
void Client::mcdata_media_file_answer(const std::string &decision) {
  if (!mcdata_media_pending_invite_) throw std::runtime_error("no FD invitation waits for the user");
  const auto invite = *mcdata_media_pending_invite_;
  mcdata_media_pending_invite_.reset();
  SipResponse response;
  if (decision == "accept") {
    mcdata_media_user_accepted_ = true;
    try {
      response = mcdata_media_invite(invite);
    } catch (...) {
      mcdata_media_user_accepted_ = false;
      throw;
    }
    mcdata_media_user_accepted_ = false;
  } else {
    response.status_code = 480;
    response.headers = {{"Warning", decision == "defer" ? "399 mcdata \"231 user deferred the call invitation\""
                                                        : "399 mcdata \"110 user declined the call invitation\""}};
  }
  if (!mcdata_media_files_.empty() && mcdata_media_files_.back().value("state", "") == "offered")
    mcdata_media_files_.back()["state"] = decision == "accept" ? "accepted" : decision == "defer" ? "deferred"
                                                                                                  : "declined";
  sip_.answer(invite.dialog_id, response);
  event("mcdata_media_file_answered", {{"decision", decision}, {"status", response.status_code}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.4.2.4 and clause 24.2.2.1.2: the session kind and the in-progress priority state the
// invitation brings.
void Client::mcdata_media_session_state(const SipMessage &invite, const mcdata::Info &info, bool adhoc_session) {
  mcdata_media_session_ =
      info.request_type == "one-to-one-sds-session" || info.request_type == "group-sds-session" || adhoc_session;
  if (info.request_type == "group-sds-session" || adhoc_session) mcdata_session_group_ = info.calling_group;
  mcdata_session_adhoc_ = adhoc_session;
  if (!info.calling_group.empty() && (info.emergency || info.imminent_peril)) {
    // TS 24.282 clause 9.2.4.2.4 steps 4A and 4B: the group is in an in-progress priority state.
    mcdata_group_priority_received(info);
    // TS 24.282 clause 9.2.4.2.4 step 4A a iii: the emergency alert that came with the communication.
    if (info.alert == true) mcdata_alert_incoming(info, invite.parts);
    event("mcdata_group_priority_received", {{"from", info.calling_user},
                                             {"group", info.calling_group},
                                             {"emergency", mdeg_ == 2},
                                             {"imminent", mdig_ == 2},
                                             {"alert", info.alert == true}});
  } else if (info.emergency == true) {
    // TS 24.282 clause 6.2.8.4.2: the other party made this an emergency communication.
    mcdata_session_emergency_ = true;
    mcdata_emergency_ = true;
    mdepc_ = 3;
    event("mcdata_session_emergency_received", {{"from", info.calling_user}});
  }
}

//------------------------------------------------------------------------------
// TS 33.180 clause 8.3 and clause 8.4: the key (GMK or PCK) of the session; what this participant sends in a
// session is protected like what it receives.
void Client::mcdata_media_select_key(const mcdata::Info &info, bool adhoc) {
  mcdata_media_key_.reset();
  if (mcdata_protected() && !adhoc) {
    if (!info.calling_group.empty()) {
      try {
        mcdata_media_key_ = mcdata_gmk_for(info.calling_group);
      } catch (const std::exception &error) {
        // No GMK of the group yet: the session has no key, and protected media of it is refused when it arrives.
        event("mcdata_media_key_unavailable", {{"group", info.calling_group}, {"reason", error.what()}});
      }
    } else {
      expire_data_keys();
      for (const auto &[id, context] : mcdata_pcks_)
        if (context.peer == info.calling_user) mcdata_media_key_ = context.key;
    }
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.3.2.2, clause 9.2.4.2.2 and clause 10.2.5.2.2: the SDP answer ("active", RFC 4145) and the
// MSRP connection to the server's stream.
void Client::mcdata_media_answer_offer(SipResponse &response, const SipMessage &invite, const MsrpMedia &offer,
                                       const std::string &address, bool file_transfer, bool adhoc_session) {
  MsrpMedia answer;
  answer.address = address;
  answer.port = 9; // RFC 4145: the active endpoint does not listen
  answer.path = msrp::Uri{address, msrp::random_ident(), "tcp", 9, false}.text();
  answer.setup = "active";
  // TS 24.282 clause 9.2.4.2.2 step 1c: "a=sendrecv" for an SDS session (one-to-one, group, ad hoc); clause
  // 9.2.3.2.2 step 1d: "a=recvonly" for a standalone SDS or a file
  answer.direction = mcdata_media_session_ || adhoc_session ? "sendrecv" : "recvonly";
  answer.accept_types = {mcdata::signalling_mime, mcdata::payload_mime};
  if (file_transfer) {
    // TS 24.282 clause 10.2.5.2.2: the answer names the signalling type and repeats the file selector.
    answer.accept_types = {mcdata::signalling_mime};
    answer.file_selector = offer.file_selector;
    answer.file_transfer_id = offer.file_transfer_id; // RFC 5547 clause 8.2.2
  }
  mcdata_media_answer_ = msrp_sdp(answer, 1);
  response.local_sdp = mcdata_media_answer_;
  mcdata_media_dialog_ = invite.dialog_id;
  const auto peer = msrp::parse_uri(offer.path);
  const auto local = msrp::parse_uri(answer.path);
  schedule([this, peer, local] {
    if (!mcdata_media_) return;
    mcdata_media_role_ = "active";
    mcdata_media_->connect(
        peer, local,
        {[this](const msrp::Message &received) { mcdata_media_incoming(received); },
         [this](std::string_view reason) { event("mcdata_media_failed", {{"reason", std::string(reason)}}); },
         {}}); // the terminating side releases nothing when its SENDs are answered
  });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.8.1.17: release the communication over the media plane (SIP BYE request) and return the
// communication states.
void Client::mcdata_media_close(const std::vector<SipHeader> &bye_headers) {
  if (!mcdata_media_dialog_.empty()) {
    const auto dialog = mcdata_media_dialog_;
    mcdata_media_dialog_.clear();
    schedule([this, dialog, bye_headers] { sip_.bye(dialog, {}, bye_headers); });
  }
  mcdata_media_.reset();
  ++mcdata_media_serial_; // the notifications of its messages now go by SIP MESSAGE (TS 24.282 clause 9.2.4.2.4)
  mcdata_media_pending_.clear();
  mcdata_media_role_.clear();
  mcdata_media_session_ = false;
  mcdata_session_emergency_ = false;
  mcdata_session_imminent_ = false;
  mcdata_session_group_.clear();
  mcdata_session_adhoc_ = false;
  mcdata_media_file_.clear();
  mcdata_media_offered_ = nullptr;
  mcdata_media_key_.reset();
  mcdata_media_peer_.clear();
  mdepc_ = 1;

  // TS 24.282 clause 6.2.8.1.17: releasing the communication returns the communication states, and
  // a request that was still pending; the in-progress state of the group itself
  // lasts until it is cancelled, so MDEG 2 and MDIG 2 are left alone.
  mdegc_ = 1;
  mdigc_ = 1;
  if (mdeg_ == 4) mdeg_ = 1;
  if (mdig_ == 4) mdig_ = 1;
}

//------------------------------------------------------------------------------
// TS 24.582 clauses 6.1.1.3.2 and 6.1.2.6: the MCData bodies of a received MSRP SEND: an SDS NOTIFICATION, or an SDS
// message whose DATA PAYLOAD is verified (TS 33.180 clause 8.5.5) and opened (TS 33.180 clause 8.5.4).
void Client::mcdata_media_incoming(const msrp::Message &message) {
  if (message.content_type == mcdata::file_mime) return mcdata_media_file_received(message.body);
  auto bodies = message.parts();
  if (mcdata_signalling_key()) {
    std::vector<BodyPart> parts;
    for (const auto &[type, value] : bodies) parts.push_back({type, value});
    try {
      parts = mcdata_open_signalling(std::move(parts));
    } catch (const std::exception &error) {
      ++mcdata_signalling_refused_;
      event("mcdata_signalling_refused", {{"media", true}, {"reason", error.what()}});
      return;
    }
    bodies.clear();
    for (const auto &part : parts) bodies[part.content_type] = part.body;
  }
  const auto signalling = bodies.find(std::string(mcdata::signalling_mime));
  if (signalling == bodies.end()) return;
  try {
    if (mcdata::message_type(signalling->second) == mcdata::MessageType::SdsNotification) {
      // TS 24.582 clause 6.1.2.5: an in-band disposition notification of this session.
      const auto notice = mcdata::decode_sds_notification(signalling->second);
      bounded_push(mcdata_media_notifications_,
                   {{"from", notice.sender ? Json(*notice.sender) : Json(mcdata_media_result_.value("from", ""))},
                    {"type", static_cast<int>(notice.type)},
                    {"message_id", mcdata::uuid_text(notice.message)},
                    {"group", mcdata_media_group_}});
      event("mcdata_session_notification_received",
            {{"message_id", mcdata::uuid_text(notice.message)}, {"type", static_cast<int>(notice.type)}});
      return;
    }
    const auto sds = mcdata::decode_sds_signalling(signalling->second);
    Json received = {{"message_id", mcdata::uuid_text(sds.message)},
                     {"conversation_id", mcdata::uuid_text(sds.conversation)},
                     {"from", mcdata_media_result_.is_object() ? mcdata_media_result_.value("from", "") : ""},
                     {"group", mcdata_media_group_},
                     {"payloads", Json::array()}};
    std::string data_body;
    if (const auto data = bodies.find(std::string(mcdata::payload_mime)); data != bodies.end())
      data_body = data->second;
    if (sds.sender) received["from"] = *sds.sender;
    received["authenticated"] = false;
    if (!data_body.empty() && (static_cast<std::uint8_t>(data_body[0]) & 0x80)) {
      try {
        data_body = mcdata_verify(data_body, received.at("from").get<std::string>());
        received["authenticated"] = true;
      } catch (const std::exception &error) {
        ++mcdata_signature_failures_;
        event("mcdata_signature_rejected", {{"from", received.at("from")}, {"reason", error.what()}, {"media", true}});
        return;
      }
    }
    // A protected DATA PAYLOAD (bit 7) is opened with the PCK of the peer or the GMK
    // of the group; one that cannot be opened is not rendered.
    if (!data_body.empty() && (static_cast<std::uint8_t>(data_body[0]) & 0x40)) {
      try {
        received["dppk_id"] = keying::payload_key_id(
            std::span(reinterpret_cast<const std::uint8_t *>(data_body.data()), data_body.size()));
        data_body = mcdata_open(data_body, mcdata_media_origin());
        received["protected"] = true;
      } catch (const std::exception &error) {
        ++mcdata_protection_failures_;
        event("mcdata_payload_undecryptable", {{"reason", error.what()}, {"media", true}});
        return;
      }
    } else
      received["protected"] = false;
    const auto payload = data_body.empty() ? mcdata::DataPayload{} : mcdata::decode_data_payload(data_body);
    for (const auto &part : payload.payloads)
      received["payloads"].push_back(
          {{"content_type", static_cast<int>(part.type)},
           {"text", part.type == mcdata::ContentType::Text ? Json(part.data) : Json(nullptr)},
           {"bytes", part.data.size()}});
    if (sds.sender) received["from"] = *sds.sender; // any participant of a session may send
    received["disposition"] = static_cast<int>(sds.disposition);
    bounded_push(mcdata_media_received_, received);
    event("mcdata_media_sds_received",
          {{"message_id", received.at("message_id")}, {"bytes", message.body.size()}, {"from", received.at("from")}});
    // TS 24.582 clauses 6.1.1.3.2 and 6.1.2.6: the message is handled as one of the signalling plane (TS 24.282
    // clause 9.2.1.2), its disposition request by clause 9.2.1.3 (TDU1, the display indication); mcdata_notify sends
    // the notification in this session while it lasts (TS 24.582 clause 6.1.2.5), else by SIP MESSAGE (12.2.1.1).
    const auto id = mcdata::uuid_text(sds.message);
    if (mcdata_received_.contains(id)) return;
    Json kept = {{"message_id", id},
                 {"conversation_id", received.at("conversation_id")},
                 {"in_reply_to", sds.in_reply_to ? Json(mcdata::uuid_text(*sds.in_reply_to)) : Json(nullptr)},
                 {"from", received.at("from")},
                 {"group", mcdata_media_group_},
                 {"request_type", mcdata_media_session_ ? "sds-session" : "sds-media"},
                 {"application_id", sds.application_id ? Json(*sds.application_id) : Json(nullptr)},
                 {"disposition", static_cast<int>(sds.disposition)},
                 {"displayed", false},
                 {"notified", Json::array()},
                 {"date_time", sds.date_time},
                 {"protected", received.value("protected", false)},
                 {"dppk_id", received.value("dppk_id", Json(nullptr))},
                 {"authenticated", received.value("authenticated", false)},
                 {"plane", "media"},
                 {"session_serial", mcdata_media_session_ ? Json(mcdata_media_serial_) : Json(nullptr)}};
    mcdata::Info info;
    info.calling_user = received.at("from").get<std::string>();
    info.calling_group = mcdata_media_group_;
    mcdata_deliver_sds(kept, sds, payload, id, info);
  } catch (const std::exception &error) {
    event("mcdata_media_sds_discarded", {{"reason", error.what()}});
  }
}

//------------------------------------------------------------------------------
// TS 24.582 clause 7.1.3.2: the file is complete, so it is kept and its metadata checked.
void Client::mcdata_media_file_received(const std::string &transferred) {
  Json file = mcdata_media_offered_.is_object() ? mcdata_media_offered_ : Json::object();
  // RFC 5547: the size and hash describe what was transferred; a protected file is
  // then opened (TS 33.180 clause 8.5.4) and stored in clear.
  const auto expected_transfer = file.value("sha256", std::string{});
  const bool transfer_verified = expected_transfer.empty() || expected_transfer == sha256_hex(transferred);
  std::string contents = transferred;
  const bool adhoc = mcdata_media_result_.is_object() && mcdata_media_result_.value("adhoc", false);
  if (mcdata_protected() && !adhoc) {
    try {
      contents = mcdata_open_file(transferred, mcdata_media_origin());
      file["protected"] = true;
    } catch (const std::exception &error) {
      ++mcdata_protection_failures_;
      event("mcdata_file_undecryptable", {{"reason", error.what()}, {"media", true}});
      file["state"] = "undecryptable";
      bounded_push(mcdata_media_files_, file);
      mcdata_media_offered_ = nullptr;
      return;
    }
  }
  const auto directory = std::filesystem::path(cfg_.at("status_file").get<std::string>()).parent_path() / "media-files";
  std::filesystem::create_directories(directory);
  const auto name = file.value("name", std::string("file"));
  const auto target = directory / std::filesystem::path(name).filename();
  const bool saved = save_file(target, contents);
  const auto size = file.value("bytes", std::uint64_t{0});
  file["path"] = target.string();
  file["received_bytes"] = transferred.size();
  file["plain_bytes"] = contents.size();
  file["plain_sha256"] = sha256_hex(contents);
  file["hash_verified"] = expected_transfer.empty() ? Json(nullptr) : Json(transfer_verified);
  file["state"] = !saved                                                     ? "unsaved"
                  : transfer_verified && (!size || size == transferred.size()) ? "received"
                                                                               : "corrupt";
  bounded_push(mcdata_media_files_, file);
  event("mcdata_media_file_received",
        {{"message_id", file.value("message_id", "")}, {"bytes", contents.size()}, {"state", file.at("state")}});
  // TS 24.282 clause 12.2.1.1 with clause 6.2.3.2: the completion notification travels on the signalling plane.
  if (file.value("completion_requested", false) && !file.value("from", std::string{}).empty()) {
    try {
      mcdata_fd_notify(file, mcdata::FdNotificationType::Completed);
    } catch (const std::exception &error) {
      failure(std::string("FD completion notification: ") + error.what());
    }
  }
  mcdata_media_offered_ = nullptr;
}

//------------------------------------------------------------------------------
// TS 24.582 clauses 6.1.1.2.1 and 7.1.2.1: the MSRP loop: send the pending SDS or file once the connection is bound,
// and end a standalone transfer once its SEND is answered.
void Client::mcdata_media_tick() {
  if (!mcdata_media_) return;
  mcdata_media_->poll();
  if (!mcdata_media_pending_.empty() && mcdata_media_->connected()) {
    // TS 24.582 clause 6.1.1.2.1: the payload follows the binding of the connection.
    mcdata_media_->send_parts(mcdata_seal_msrp(mcdata_media_pending_));
    mcdata_media_pending_.clear();
    mcdata_media_result_["state"] = "sent";
    event("mcdata_media_sds_delivered", {{"message_id", mcdata_media_message_id_}});
  }
  if (!mcdata_media_file_.empty() && mcdata_media_->connected()) {
    // TS 24.582 clause 7.1.2.1: the file is the body of the SEND, chunked as needed.
    mcdata_media_->send(std::string(mcdata::file_mime), mcdata_media_file_);
    mcdata_media_result_["state"] = "sent";
    event("mcdata_media_file_transferred",
          {{"message_id", mcdata_media_message_id_}, {"bytes", mcdata_media_file_.size()}});
    mcdata_media_file_.clear();
  }
  if (mcdata_media_acknowledged_) {
    mcdata_media_acknowledged_ = false;
    // TS 24.582 clause 6.1.1.2.1: a standalone transfer ends once its SEND is answered; an SDS
    // session stays open until the user closes it (TS 24.282 clause 9.2.4).
    if (!mcdata_media_session_) {
      const bool file = mcdata_media_result_.is_object() && mcdata_media_result_.value("file", false);
      mcdata_media_result_["state"] = "delivered";
      // TS 24.282 clause 9.2.3.2.3, on the media plane's "successfully transferred" indication: a BYE with the
      // Reason SIP, cause 200, text "transmission succeeded" (RFC 3326)
      if (file)
        mcdata_media_close();
      else
        mcdata_media_close({{"Reason", "SIP;cause=200;text=\"transmission succeeded\""}});
    }
  }
}

} // namespace mcx
