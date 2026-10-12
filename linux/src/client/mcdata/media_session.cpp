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
  mcdata_media_group_ = info.calling_group;
  mcdata_media_peer_ = info.calling_group.empty() ? info.calling_user : std::string{};
  mcdata_media_select_key(info, adhoc);
  mcdata_media_offered_ = offered;
  mcdata_media_answer_offer(response, invite, offer, address, file_transfer, adhoc_session);
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
  response.local_sdp = mcdata_media_answer_;
  if (!info.calling_group.empty()) {
    mcdata_group_priority_received(info);
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
             {"application_id", request.application_id ? Json(*request.application_id) : Json(nullptr)}};
  // TS 24.282 clause 11.2 auto-receive, or an explicit mandatory download; otherwise the
  // invitation is declined, since these clients have no interactive user.
  const auto automatic = cfg_.at("mcdata").value("fd", Json::object()).value("auto_receive_bytes", 0u);
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
  answer.direction =
      adhoc_session ? "sendrecv" : "recvonly"; // TS 24.282 clause 9.2.4.2.2 step 1c for an ad hoc session
  answer.accept_types = {mcdata::signalling_mime, mcdata::payload_mime};
  if (file_transfer) {
    // TS 24.282 clause 10.2.5.2.2: the answer names the signalling type and repeats the file selector.
    answer.accept_types = {mcdata::signalling_mime};
    answer.file_selector = offer.file_selector;
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
    if (!data_body.empty())
      for (const auto &part : mcdata::decode_data_payload(data_body).payloads)
        received["payloads"].push_back(
            {{"content_type", static_cast<int>(part.type)},
             {"text", part.type == mcdata::ContentType::Text ? Json(part.data) : Json(nullptr)},
             {"bytes", part.data.size()}});
    if (sds.sender) received["from"] = *sds.sender; // any participant of a session may send
    received["disposition"] = static_cast<int>(sds.disposition);
    bounded_push(mcdata_media_received_, received);
    event("mcdata_media_sds_received",
          {{"message_id", received.at("message_id")}, {"bytes", message.body.size()}, {"from", received.at("from")}});
    // TS 24.582 clause 6.1.2.5.1: the disposition notification travels in the same session.
    if (mcdata_media_session_ && sds.disposition != mcdata::DispositionRequest::None) {
      mcdata::SdsNotification notice;
      notice.type = sds.disposition == mcdata::DispositionRequest::Read ? mcdata::DispositionNotification::Read
                                                                        : mcdata::DispositionNotification::Delivered;
      notice.date_time = static_cast<std::uint64_t>(std::time(nullptr));
      notice.conversation = sds.conversation;
      notice.message = sds.message;
      notice.sender = mcdata_user();
      mcdata_media_->send_parts(mcdata_seal_msrp({{std::string(mcdata::signalling_mime), mcdata::encode(notice)}}));
      event("mcdata_session_notification_sent",
            {{"message_id", received.at("message_id")}, {"type", static_cast<int>(notice.type)}});
    }
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
      mcdata_media_result_["state"] = "delivered";
      mcdata_media_close();
    }
  }
}

} // namespace mcx
