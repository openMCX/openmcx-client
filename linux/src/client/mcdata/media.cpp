//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData communications over the media plane, originating MCData client side: standalone SDS, SDS sessions (with
// their emergency and imminent peril priority) and FD over MSRP (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.582 V19.0.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// RFC 4975 (MSRP), RFC 5547 (file transfer), RFC 3261 (SIP).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.3.2.1 step 2 and TS 33.180 clause 8.4: a one-to-one offer carries a new PCK when there is no
// security context with the peer; a group communication uses the GMK. Ad hoc groups stay clear.
void Client::mcdata_media_protect(MsrpMedia &offer, const std::string &target, bool group, bool adhoc) {
  mcdata_media_key_.reset();
  mcdata_media_peer_ = group || adhoc ? std::string{} : target;
  if (!mcdata_protected() || adhoc) return;
  if (group) {
    mcdata_media_key_ = mcdata_gmk_for(target);
    return;
  }
  auto [key, message] = mcdata_pck_for(target);
  mcdata_media_key_ = key;
  offer.key_mgmt = message;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.3.2: the DATA PAYLOAD sent over the media plane, protected with the PCK or GMK of the
// communication and optionally signed (TS 33.180 clauses 8.5.4 and 8.5.5).
std::string Client::mcdata_media_payload(const std::string &encoded) {
  return mcdata_sign(mcdata_media_key_ ? mcdata_seal(encoded, *mcdata_media_key_) : encoded);
}

//------------------------------------------------------------------------------
// Helper: who sent what arrives on the media plane: the peer of a one-to-one
// communication, or the group of a group one.
mcdata::Info Client::mcdata_media_origin() const {
  mcdata::Info info;
  info.calling_user = mcdata_media_peer_;
  info.calling_group = !mcdata_media_group_.empty() ? mcdata_media_group_ : mcdata_session_group_;
  return info;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.3.2.3 steps 1 to 4: the media feature tags, ICSI and Contact of a SIP INVITE request for SDS.
std::vector<SipHeader> Client::mcdata_media_headers() const {
  // The contact is the engine's own, with the MCData feature
  // tags: it becomes the remote target of the dialog, so it has to name the user
  // and the transport the endpoint actually listens on.
  const auto contact =
      "<" + sip_.contact_target() + ">;+g.3gpp.mcdata.sds;+g.3gpp.icsi-ref=\"" + url_encode(mcdata::sds_icsi) + "\"";
  return {{"Accept-Contact", "*;+g.3gpp.mcdata.sds;require;explicit"},
          {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcdata::sds_icsi) + "\";require;explicit"},
          {"P-Preferred-Service", mcdata::sds_icsi},
          {"Contact", contact}};
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.3.2.1 step 1 with TS 24.582 clause 6.1.1.2.1: listen for the MSRP connection and build the
// "m=message" SDP offer ("a=sendonly", "a=setup:actpass").
MsrpMedia Client::mcdata_media_offer() {
  mcdata_media_ = std::make_unique<msrp::Endpoint>();
  const auto address = resolve_ipv4(cfg_.at("media").at("advertise"));
  const auto local = mcdata_media_->listen(
      address, {[this](const msrp::Message &message) { mcdata_media_incoming(message); },
                [this](std::string_view reason) { event("mcdata_media_failed", {{"reason", std::string(reason)}}); },
                [this] { mcdata_media_acknowledged_ = true; }});
  MsrpMedia media;
  media.address = address;
  media.port = local.port;
  media.path = local.text();
  media.setup = "actpass"; // TS 24.282 clause 9.2.3.2.1 step 1g
  media.direction = "sendonly";
  media.accept_types = {mcdata::signalling_mime, mcdata::payload_mime};
  return media;
}

//------------------------------------------------------------------------------
// TS 24.582 clause 6.1.1.2.1: the answer decides which endpoint connects.
void Client::mcdata_media_connect(const MsrpMedia &answer) {
  const auto peer = msrp::parse_uri(answer.path);
  if (answer.setup == "active") {
    mcdata_media_role_ = "passive"; // the peer connects to the listener we already hold
    return;
  }
  mcdata_media_role_ = "active";
  const auto local = msrp::parse_uri(mcdata_media_->local().text());
  mcdata_media_->connect(
      peer, local,
      {[this](const msrp::Message &message) { mcdata_media_incoming(message); },
       [this](std::string_view reason) { event("mcdata_media_failed", {{"reason", std::string(reason)}}); },
       [this] { mcdata_media_acknowledged_ = true; }});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.4.2.3: an SDS session (one-to-one, group or ad hoc group) over the media plane, optionally an
// emergency or imminent peril communication; unlike a standalone SDS (TS 24.282 clause 9.2.3.2.3) it stays open, and
// any participant may send into it.
void Client::mcdata_open_session(const std::string &target, bool group, const std::string &priority, bool with_alert) {
  if (!mcdata_authorized_) throw std::runtime_error("an SDS session requires MCData service authorisation");
  if (mcdata_media_) throw std::runtime_error("an MCData media plane session is already open");
  const bool emergency = priority == "emergency", imminent = priority == "imminent";

  // TS 24.282 clause 24.2.2.1.1: target "adhoc=<ad hoc group>" opens an ad hoc group SDS session.
  const bool adhoc = target.starts_with("adhoc=");
  if (adhoc && (emergency || imminent))
    throw std::runtime_error("ad hoc group emergency and imminent peril communications are not implemented");
  if (imminent && !group) throw std::runtime_error("imminent peril applies to group communications");

  // TS 24.282 clause 6.2.8.3.1.1 for a one-to-one communication, clause 6.2.8.1.8 for a group one.
  if (emergency) require_mcdata_profile({group ? "allow-emergency-group-call" : "allow-emergency-private-call"});
  if (imminent) require_mcdata_profile({"allow-imminent-peril-call"});
  mcdata::Info info;
  info.request_type = adhoc ? "adhoc-group-sds-session" : group ? "group-sds-session" : "one-to-one-sds-session";
  info.client_id = client_id();
  if (group) info.request_uri = target;
  const auto participants = adhoc ? mcdata_adhoc_target(target.substr(6), info) : std::vector<std::string>{};
  mcdata_session_priority_state(info, target, group, emergency, imminent, with_alert);
  auto offer = mcdata_media_offer();
  offer.direction = adhoc ? "sendrecv" : offer.direction; // TS 24.282 clause 9.2.4.2.1 step 1c
  mcdata_media_protect(offer, target, group, adhoc);
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (adhoc) {
    if (!participants.empty()) parts.push_back({resource_lists_mime, resource_list_xml(participants)});
  } else if (!group) {
    parts.push_back({resource_lists_mime, private_call_resource_list(target)});
  }
  if (with_alert) {
    // TS 24.282 clause 6.2.5.1: the location information that goes with an emergency alert.
    parts.push_back({mcdata::location_mime, emergency_location_xml(mcdata_emergency_location(), true)});
  }
  auto headers = mcdata_media_headers();

  // TS 24.282 clauses 6.2.8.3.3, 6.2.8.1.2 and 6.2.8.1.12: the priority of the request.
  if (emergency || imminent)
    for (const auto &header : mcdata_priority_headers(imminent ? "imminent_peril" : "emergency"))
      headers.push_back(header);
  mcdata_session_group_ = group ? target : std::string{};
  mcdata_session_adhoc_ = adhoc;
  mcdata_media_session_ = true;
  mcdata_media_conversation_ = mcdata::random_uuid(); // one conversation for the session
  mcdata_media_result_ = {{"target", target},       {"group", group ? target : std::string{}},
                          {"status", nullptr},      {"state", "inviting"},
                          {"session", true},        {"adhoc", adhoc},
                          {"emergency", emergency}, {"imminent", imminent},
                          {"priority", priority}};
  mcdata_media_dialog_ = sip_.invite_sdp(
      mcdata_service(), msrp_sdp(offer, 1), mcdata_seal_signalling(parts),
      [this](const SipResponse &raw) { mcdata_session_answered(raw); }, headers);
  event("mcdata_session_opened", {{"target", target}, {"group", group}, {"adhoc", adhoc}, {"priority", priority}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.8.3.2 (one-to-one emergency), clause 6.2.8.1.1 (emergency group, with an optional emergency
// alert) and clause 6.2.8.1.9 (imminent peril group): the priority of an SDS session request and the states it moves.
void Client::mcdata_session_priority_state(mcdata::Info &info, const std::string &target, bool group, bool emergency,
                                           bool imminent, bool with_alert) {
  if (emergency && !group) {
    // TS 24.282 clause 6.2.8.3.2: the request carries <emergency-ind> and the state moves to MDEPC 2.
    info.emergency = true;
    info.alert = false;
    mdepc_ = 2;
    mcdata_session_emergency_ = true;
    mcdata_emergency_ = true;
  } else if (emergency) {
    // TS 24.282 clause 6.2.8.1.1: an emergency group communication, with the MCData emergency state set.
    info.emergency = true;
    info.alert = with_alert; // TS 24.282 clause 6.2.8.1.1 step 3a or step 4
    mdegc_ = 2;
    if (mdeg_ != 2) mdeg_ = 4;
    mcdata_session_emergency_ = true;
    mcdata_emergency_ = true;
    if (with_alert) {
      // TS 24.282 clause 6.2.8.1.1 step 3: the alert needs its own authorisation (clause 6.2.8.1.6) and location.
      require_mcdata_profile({"allow-activate-emergency-alert"});
      mdea_ = 2; // MDEA 2: emergency-alert-confirm-pending
      mcdata_alert_group_ = target;
    }
  } else if (imminent) {
    // TS 24.282 clause 6.2.8.1.9: an imminent peril group communication; no MCData emergency state.
    info.imminent_peril = true;
    mdigc_ = 2;
    if (mdig_ != 2) mdig_ = 4;
    mcdata_session_imminent_ = true;
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 9.2.3.2.3 and 10.2.5.2.3: the response to the INVITE of a standalone SDS or file over the media
// plane (what names it in the error): the MSRP connection on a 2xx, the release on a failure.
void Client::mcdata_media_answered(const SipResponse &raw, const char *what) {
  const auto response = mcdata_opened_response(raw);
  if (response.status_code < 200) return;
  mcdata_media_result_["status"] = response.status_code;
  mcdata_media_result_["warning"] = std::string(get_header(response.headers, "Warning"));
  if (response.status_code >= 300) {
    mcdata_media_result_["state"] = "rejected";
    mcdata_media_close();
    failure(std::string("media plane ") + what + ": " + std::to_string(response.status_code));
    return;
  }
  try {
    for (const auto &part : response.parts)
      if (part.content_type.starts_with("application/sdp")) {
        mcdata_media_connect(parse_msrp_sdp(part.body));
        mcdata_media_result_["state"] = "connecting";
      }
    mcdata_adhoc_answered(response);
  } catch (const std::exception &error) {
    mcdata_media_result_["state"] = "sdp-failed";
    failure(error.what());
    mcdata_media_close();
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.4.2.3: the response to an SDS session request: the MSRP connection on a 2xx (with the
// granted emergency condition, clause 6.2.8.3.4, and priority, clause 6.2.8.1.4), the release on a failure.
void Client::mcdata_session_answered(const SipResponse &raw) {
  const auto response = mcdata_opened_response(raw);
  if (response.status_code < 200) return;
  mcdata_media_result_["status"] = response.status_code;
  mcdata_media_result_["warning"] = std::string(get_header(response.headers, "Warning"));
  if (response.status_code >= 300) {
    mcdata_media_result_["state"] = "rejected";
    mcdata_priority_response(response);
    mcdata_media_close();
    failure("SDS session: " + std::to_string(response.status_code));
    return;
  }
  try {
    for (const auto &part : response.parts)
      if (part.content_type.starts_with("application/sdp")) {
        mcdata_media_connect(parse_msrp_sdp(part.body));
        mcdata_media_result_["state"] = "open";
      }
    mcdata_adhoc_answered(response);
    // TS 24.282 clause 6.2.8.3.4: the emergency condition is granted by the 2xx.
    if (mdepc_ == 2) mdepc_ = 3;
    mcdata_priority_response(response); // TS 24.282 clause 6.2.8.1.4
  } catch (const std::exception &error) {
    failure(error.what());
    mcdata_media_close();
  }
}

//------------------------------------------------------------------------------
// A re-INVITE inside an open session changes its priority: TS 24.282 clauses 6.2.8.4.4 and 6.2.8.4.3
// for a one-to-one communication, clauses 6.2.8.1.1, 6.2.8.1.3, 6.2.8.1.9 and 6.2.8.1.11
// for the emergency and imminent peril states of a group.
void Client::mcdata_session_priority(bool emergency, bool enable) {
  if (!mcdata_media_ || !mcdata_media_session_ || mcdata_media_dialog_.empty())
    throw std::runtime_error("no MCData SDS session is open");
  const bool group = !mcdata_session_group_.empty();
  if (mcdata_session_adhoc_)
    throw std::runtime_error("ad hoc group emergency and imminent peril communications are not implemented");
  if (!emergency && !group) throw std::runtime_error("imminent peril applies to group communications");
  if (group) return mcdata_group_priority(emergency, enable);
  if (enable)
    require_mcdata_profile({"allow-emergency-private-call"}); // TS 24.282 clause 6.2.8.3.1.1
  else
    require_mcdata_profile({"allow-cancel-private-emergency-call"}); // TS 24.282 clause 6.2.8.3.1.2
  if (enable == mcdata_session_emergency_)
    throw std::runtime_error(enable ? "the session is already an emergency communication"
                                    : "the session is not an emergency communication");
  mcdata::Info info;
  info.request_type = "one-to-one-sds-session";
  info.client_id = client_id();
  info.emergency = enable;
  info.alert = false;
  if (enable) {
    mdepc_ = 2;
    mcdata_emergency_ = true;
  }
  auto headers = mcdata_media_headers();

  // TS 24.282 clause 6.2.8.3.3: the emergency priority applies while the condition is requested or granted.
  for (const auto &header : mcdata_priority_headers()) headers.push_back(header);
  const bool emergency_request = enable;
  const auto offer = mcdata_media_->local();
  MsrpMedia media;
  media.address = resolve_ipv4(cfg_.at("media").at("advertise"));
  media.port = mcdata_media_->port();
  media.path = offer.text();
  media.setup = "actpass";
  media.direction = "sendonly";
  media.accept_types = {mcdata::signalling_mime, mcdata::payload_mime};
  sip_.reinvite_sdp(
      mcdata_media_dialog_, msrp_sdp(media, 1), mcdata_seal_signalling({{mcdata::info_mime, mcdata::info_xml(info)}}),
      [this, emergency_request](const SipResponse &raw) {
        const auto response = mcdata_opened_response(raw);
        if (response.status_code < 200) return;
        mcdata_media_result_["emergency_status"] = response.status_code;
        mcdata_media_result_["emergency_warning"] = std::string(get_header(response.headers, "Warning"));
        if (response.status_code >= 300) {
          // TS 24.282 clause 6.2.8.3.5: the request failed, so the state returns.
          mdepc_ = mcdata_session_emergency_ ? 3 : 1;
          failure("session emergency: " + std::to_string(response.status_code));
          return;
        }
        mcdata_session_emergency_ = emergency_request;
        mcdata_media_result_["emergency"] = emergency_request;
        mdepc_ = emergency_request ? 3 : 1; // TS 24.282 clause 6.2.8.3.4
        if (!emergency_request) mcdata_emergency_ = false;
        event("mcdata_session_emergency", {{"emergency", emergency_request}, {"state", mdepc_name(mdepc_)}});
      },
      headers);
  event("mcdata_session_emergency_requested", {{"emergency", emergency_request}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 6.2.8.1.1, 6.2.8.1.3, 6.2.8.1.9 and 6.2.8.1.11: the group counterpart: the in-progress emergency
// or imminent peril state of the group of this session is raised or cancelled with a re-INVITE.
void Client::mcdata_group_priority(bool emergency, bool enable) {
  if (emergency && enable) require_mcdata_profile({"allow-emergency-group-call"}); // TS 24.282 clause 6.2.8.1.8
  // TS 24.282 clause 6.2.8.1.7: cancelling the group's emergency state is left to local policy (no profile right).
  if (!emergency && enable) require_mcdata_profile({"allow-imminent-peril-call"});    // TS 24.282 clause 6.2.8.1.8
  if (!emergency && !enable) require_mcdata_profile({"allow-cancel-imminent-peril"}); // TS 24.282 clause 6.2.8.1.10
  if (emergency && enable && mdeg_ == 2) throw std::runtime_error("the group is already in an emergency state");
  if (emergency && !enable && mdeg_ != 2) throw std::runtime_error("the group is not in an emergency state");
  if (!emergency && enable && mdig_ == 2) throw std::runtime_error("the group is already in an imminent peril state");
  if (!emergency && !enable && mdig_ != 2) throw std::runtime_error("the group is not in an imminent peril state");
  mcdata::Info info;
  info.request_type = "group-sds-session";
  info.client_id = client_id();
  info.request_uri = mcdata_session_group_;
  if (emergency) {
    info.emergency = enable;
    info.alert = false;
    if (enable) { // TS 24.282 clause 6.2.8.1.1
      mdegc_ = 2;
      if (mdeg_ != 2) mdeg_ = 4;
      mcdata_emergency_ = true;
    } else { // TS 24.282 clause 6.2.8.1.3
      mdeg_ = 3;
      mcdata_emergency_ = false;
    }
  } else {
    info.imminent_peril = enable;
    if (enable) { // TS 24.282 clause 6.2.8.1.9
      mdigc_ = 2;
      if (mdig_ != 2) mdig_ = 4;
    } else { // TS 24.282 clause 6.2.8.1.11
      mdig_ = 3;
    }
  }
  auto headers = mcdata_media_headers();

  // TS 24.282 clauses 6.2.8.1.2 and 6.2.8.1.12: a cancellation carries the normal priority.
  const char *level = !enable ? "normal" : emergency ? "emergency" : "imminent_peril";
  for (const auto &header : mcdata_priority_headers(level)) headers.push_back(header);
  std::string offer_sdp = mcdata_media_answer_; // the media this client already holds
  if (offer_sdp.empty()) {
    MsrpMedia media;
    media.address = resolve_ipv4(cfg_.at("media").at("advertise"));
    media.port = mcdata_media_->port();
    media.path = mcdata_media_->local().text();
    media.setup = "actpass";
    media.direction = "sendonly";
    media.accept_types = {mcdata::signalling_mime, mcdata::payload_mime};
    offer_sdp = msrp_sdp(media, 1);
  }
  sip_.reinvite_sdp(
      mcdata_media_dialog_, offer_sdp, mcdata_seal_signalling({{mcdata::info_mime, mcdata::info_xml(info)}}),
      [this, emergency, enable](const SipResponse &raw) {
        const auto response = mcdata_opened_response(raw);
        if (response.status_code < 200) return;
        mcdata_media_result_["priority_status"] = response.status_code;
        mcdata_media_result_["priority_warning"] = std::string(get_header(response.headers, "Warning"));
        if (response.status_code >= 300) {
          mcdata_priority_response(response); // TS 24.282 clause 6.2.8.1.5
          failure("group priority: " + std::to_string(response.status_code));
          return;
        }
        if (enable) {
          mcdata_priority_response(response); // TS 24.282 clause 6.2.8.1.4
        } else if (emergency) {
          mdeg_ = 1;
          mdegc_ = 1;
          mcdata_session_emergency_ = false;
        } else {
          mdig_ = 1;
          mdigc_ = 1;
          mcdata_session_imminent_ = false;
        }
        mcdata_media_result_["emergency"] = mcdata_session_emergency_;
        mcdata_media_result_["imminent"] = mcdata_session_imminent_;
        event("mcdata_group_priority", {{"emergency", mdeg_ == 2},
                                        {"imminent", mdig_ == 2},
                                        {"state", emergency ? mdeg_name(mdeg_) : mdig_name(mdig_)}});
      },
      headers);
  event("mcdata_group_priority_requested", {{"emergency", emergency}, {"enable", enable}});
}

//------------------------------------------------------------------------------
// TS 24.582 clause 6.1.2.4: an SDS message inside an open session.
void Client::mcdata_session_send(const std::string &text, mcdata::DispositionRequest disposition) {
  if (!mcdata_media_ || !mcdata_media_session_) throw std::runtime_error("no MCData SDS session is open");
  require_mcdata_profile({"allow-transmit-data"});
  mcdata::SdsSignalling signalling;
  signalling.date_time = static_cast<std::uint64_t>(std::time(nullptr));
  signalling.conversation = mcdata_media_conversation_;
  signalling.message = mcdata::random_uuid();
  signalling.disposition = disposition;
  signalling.sender = mcdata_user(); // any participant may send, so the sender is named
  mcdata::DataPayload payload{{{mcdata::ContentType::Text, text}}};
  mcdata_media_->send_parts(
      mcdata_seal_msrp({{std::string(mcdata::signalling_mime), mcdata::encode(signalling)},
                        {std::string(mcdata::payload_mime), mcdata_media_payload(mcdata::encode(payload))}}));
  const auto id = mcdata::uuid_text(signalling.message);
  bounded_push(mcdata_media_sent_,
               {{"message_id", id}, {"text", text}, {"disposition", static_cast<int>(disposition)}});
  event("mcdata_session_sds_sent", {{"message_id", id}, {"disposition", static_cast<int>(disposition)}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 10.2.5.2.1 and 10.2.5.2.3 with TS 24.582 clause 7.1.2: offer the file on an MSRP
// stream, with its metadata in the SDP file attributes of RFC 5547.
void Client::mcdata_send_file_media(const std::string &target, bool group, const std::string &path, bool mandatory,
                                    bool completion) {
  if (!mcdata_authorized_) throw std::runtime_error("file distribution requires MCData service authorisation");
  require_mcdata_profile({"allow-transmit-data"}); // TS 24.282 clause 11.1 step 1
  if (mcdata_media_) throw std::runtime_error("an MCData media plane session is already open");
  std::ifstream file(path, std::ios::binary);
  if (!file) throw std::runtime_error("cannot read " + path);
  std::string contents((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  const auto name = std::filesystem::path(path).filename().string();
  const bool adhoc = target.starts_with("adhoc="); // TS 24.282 clause 24.2.2.1.1 step 6
  MsrpMedia protection;
  mcdata_media_protect(protection, target, group, adhoc);
  const auto plain_bytes = contents.size();

  // RFC 5547: the size and hash selectors describe what is transferred, which for a
  // protected file is its series of Protected Payload messages.
  if (mcdata_media_key_) contents = mcdata_seal_file(contents, *mcdata_media_key_);
  const auto metadata =
      mcdata::file_metadata(name, contents.size(), "application/octet-stream", "", 0, sha256_hex(contents));
  mcdata::Info info;
  info.request_type = adhoc ? "adhoc-group-fd-session" : group ? "group-fd" : "one-to-one-fd";
  info.client_id = client_id();
  if (group) info.request_uri = target;
  const auto participants = adhoc ? mcdata_adhoc_target(target.substr(6), info) : std::vector<std::string>{};
  mcdata::FdSignalling signalling; // TS 24.282 clause 6.2.2.3: no Payload IE, no metadata IE
  signalling.date_time = static_cast<std::uint64_t>(std::time(nullptr));
  signalling.conversation = mcdata::random_uuid();
  signalling.message = mcdata::random_uuid();
  signalling.mandatory_download = mandatory;
  signalling.completion_requested = completion;
  auto offer = mcdata_media_offer();
  offer.key_mgmt = protection.key_mgmt;
  offer.accept_types = {mcdata::signalling_mime}; // TS 24.282 clause 10.2.5.2.1 step 1e
  offer.file_selector =
      metadata.substr(std::strlen("file-selector:"), metadata.find("\r\n") - std::strlen("file-selector:"));
  offer.file_date = "creation:\"" + mcdata::date_time_5322(static_cast<std::uint64_t>(std::time(nullptr))) + "\"";
  offer.file_description = name;
  mcdata_media_file_ = contents;
  mcdata_media_message_id_ = mcdata::uuid_text(signalling.message);
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (adhoc) {
    if (!participants.empty()) parts.push_back({resource_lists_mime, resource_list_xml(participants)});
  } else if (!group) {
    parts.push_back({resource_lists_mime, private_call_resource_list(target)});
  }
  parts.push_back({mcdata::signalling_mime, mcdata::encode(signalling)});
  auto headers = mcdata_fd_headers();
  headers.push_back({"Contact", "<" + sip_.contact_target() + ">;+g.3gpp.mcdata.fd;+g.3gpp.icsi-ref=\"" +
                                    url_encode(mcdata::fd_icsi) + "\""});
  mcdata_media_result_ = {{"message_id", mcdata_media_message_id_},
                          {"target", target},
                          {"group", group ? target : std::string{}},
                          {"status", nullptr},
                          {"state", "inviting"},
                          {"file", true},
                          {"name", name},
                          {"bytes", contents.size()},
                          {"sha256", sha256_hex(contents)},
                          {"mandatory", mandatory},
                          {"adhoc", adhoc},
                          {"protected", mcdata_media_key_.has_value()},
                          {"plain_bytes", plain_bytes},
                          {"dppk_id", mcdata_media_key_ ? Json(mcdata_media_key_->csb_id) : Json(nullptr)}};
  mcdata_media_dialog_ = sip_.invite_sdp(
      mcdata_service(), msrp_sdp(offer, 1), mcdata_seal_signalling(parts),
      [this](const SipResponse &raw) { mcdata_media_answered(raw, "FD"); },
      headers);
  event("mcdata_media_file_sent", {{"message_id", mcdata_media_message_id_},
                                   {"target", target},
                                   {"bytes", contents.size()},
                                   {"mandatory", mandatory}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.3.2.3 with TS 24.582 clause 6.1.1.2: a standalone SDS message whose payload travels over the
// media plane; the payload is sent once the MSRP connection is bound.
void Client::mcdata_send_media_sds(const std::string &target, const std::string &text, bool group) {
  if (!mcdata_authorized_) throw std::runtime_error("SDS requires MCData service authorisation");
  require_mcdata_profile({"allow-transmit-data"});
  if (mcdata_media_) throw std::runtime_error("an MCData media plane session is already open");
  const bool adhoc = target.starts_with("adhoc="); // TS 24.282 clause 24.2.2.1.1 step 5
  mcdata::Info info;
  info.request_type = adhoc ? "adhoc-group-sds-session" : group ? "group-sds" : "one-to-one-sds";
  info.client_id = client_id();
  if (group) info.request_uri = target; // TS 24.282 clause 9.2.3.2.3: the group, with no resource-lists
  const auto participants = adhoc ? mcdata_adhoc_target(target.substr(6), info) : std::vector<std::string>{};
  mcdata::SdsSignalling signalling;
  signalling.date_time = static_cast<std::uint64_t>(std::time(nullptr));
  signalling.conversation = mcdata::random_uuid();
  signalling.message = mcdata::random_uuid();
  mcdata::DataPayload payload{{{mcdata::ContentType::Text, text}}};
  mcdata_media_message_id_ = mcdata::uuid_text(signalling.message);
  mcdata_media_pending_ = {{std::string(mcdata::signalling_mime), mcdata::encode(signalling)},
                           {std::string(mcdata::payload_mime), mcdata::encode(payload)}};
  auto offer = mcdata_media_offer(); // "a=sendonly": a standalone SDS (TS 24.282 clause 9.2.3.2.1)
  mcdata_media_protect(offer, target, group, adhoc);
  mcdata_media_pending_[1].second = mcdata_media_payload(mcdata_media_pending_[1].second);
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (adhoc) {
    if (!participants.empty()) parts.push_back({resource_lists_mime, resource_list_xml(participants)});
  } else if (!group) {
    parts.push_back({resource_lists_mime, private_call_resource_list(target)});
  }
  mcdata_media_result_ = {{"message_id", mcdata_media_message_id_},
                          {"target", target},
                          {"group", group ? target : std::string{}},
                          {"status", nullptr},
                          {"state", "inviting"},
                          {"adhoc", adhoc},
                          {"protected", mcdata_media_key_.has_value()},
                          {"dppk_id", mcdata_media_key_ ? Json(mcdata_media_key_->csb_id) : Json(nullptr)}};
  mcdata_media_dialog_ = sip_.invite_sdp(
      mcdata_service(), msrp_sdp(offer, 1), mcdata_seal_signalling(parts),
      [this](const SipResponse &raw) { mcdata_media_answered(raw, "SDS"); },
      mcdata_media_headers());
  event("mcdata_media_sds_sent", {{"message_id", mcdata_media_message_id_}, {"target", target}});
}

} // namespace mcx
