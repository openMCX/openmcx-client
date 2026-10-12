//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData communication release over the media plane, MCData client side (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), RFC 6086 (SIP INFO), RFC 3326 (Reason header field).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: the reason-text of a Reason header field (RFC 3326 section 2), or the whole header field without one.
std::string Client::reason_text(const std::string &header) {
  const auto start = header.find("text=\"");
  if (start == std::string::npos) return header;
  const auto end = header.find('"', start + 6);
  return header.substr(start + 6, end == std::string::npos ? std::string::npos : end - start - 6);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 13.2.4.2.1 step 2c: the remaining amount of data, in bytes, this client still has to send.
std::size_t Client::mcdata_remaining_bytes() const {
  std::size_t remaining = mcdata_media_file_.size();
  for (const auto &[type, body] : mcdata_media_pending_) remaining += body.size();
  return remaining;
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 13.2.4.2.2, 13.2.5.2.2.1, 13.2.6.2.2.1 and 13.2.6.2.2.4: send a SIP INFO request with the
// g.3gpp.mcdata-com-release Info-Package in the dialog of the MCData communication, with an optional Reason header.
void Client::mcdata_com_release_send(const std::string &dialog, const std::vector<BodyPart> &parts,
                                     const std::string &reason, const std::string &action) {
  std::vector<SipHeader> headers{{"Info-Package", mcdata::com_release_package},
                                 {"Content-Disposition", "Info-Package"}};
  if (!reason.empty()) headers.push_back({"Reason", "SIP;cause=200;text=\"" + reason + "\""});
  auto answered = [this, action](const SipResponse &response) {
    // TS 24.282 clauses 13.2.5.2.2.1 and 13.2.6.2.2.1: 200 accepts the request, 403 refuses it.
    mcdata_release_["last_action"] = action;
    mcdata_release_["last_status"] = response.status_code;
    if (response.status_code >= 300)
      failure("communication release " + action + ": " + std::to_string(response.status_code));
  };
  const auto sealed = mcdata_seal_signalling(parts);
  if (sealed.size() == 1) {
    sip_.request_in_dialog(dialog, "INFO", sealed.front().content_type, sealed.front().body, headers, answered);
  } else {
    const std::string boundary = "mcdata-com-release";
    sip_.request_in_dialog(dialog, "INFO", "multipart/mixed;boundary=" + boundary, multipart_body(sealed, boundary),
                           headers, answered);
  }
  event("mcdata_com_release_sent", {{"action", action}, {"reason", reason}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 13.2.4.2.2, 13.2.5.2.2.1, 13.2.6.2.2.1 and 13.2.6.2.2.4: the user command
// mcdata-release [ipcon] extend | now [reason] | intent [query] [reason] | accept | reject
// builds the COMMUNICATION RELEASE message (TS 24.282 clause 15.1.10) of the request.
void Client::mcdata_release_command(std::istringstream &words) {
  std::string word, action, reason;
  words >> word;
  const bool tunnel = word == "ipcon";
  if (tunnel) words >> word;
  action = word;
  const auto dialog = tunnel ? ipcon_dialog_ : mcdata_media_dialog_;
  if (dialog.empty()) throw std::runtime_error("no communication is open over the media plane");
  mcdata::CommunicationRelease message;
  bool with_info = false;
  if (action == "extend") {
    message.type = mcdata::CommReleaseType::ExtensionRequest; // TS 24.282 clause 13.2.4.2.2
  } else if (action == "now") {
    message.type = mcdata::CommReleaseType::AuthUserReleaseRequest; // TS 24.282 clause 13.2.5.2.2.1
    with_info = true;
  } else if (action == "intent") {
    message.type = mcdata::CommReleaseType::IntentToRelease; // TS 24.282 clause 13.2.6.2.2.1
    with_info = true;
  } else if (action == "accept" || action == "reject") {
    message.type = mcdata::CommReleaseType::ExtensionResponse; // TS 24.282 clause 13.2.6.2.2.4
    message.extension = action == "accept" ? mcdata::ExtensionResponse::Accepted : mcdata::ExtensionResponse::Rejected;
  } else {
    throw std::runtime_error(
        "usage: mcdata-release [ipcon] extend | now [reason] | intent [query] [reason] | accept | reject");
  }
  while (words >> word) {
    if (word == "query" && action == "intent" && reason.empty())
      message.remaining_data_query = true;
    else
      reason += (reason.empty() ? "" : " ") + word;
  }
  std::vector<BodyPart> parts{{mcdata::signalling_mime, mcdata::encode(message)}};
  if (with_info) {
    mcdata::Info info;
    info.client_id = client_id(); // TS 24.282 clauses 13.2.5.2.2.1 and 13.2.6.2.2.1 step 4a
    parts.insert(parts.begin(), {mcdata::info_mime, mcdata::info_xml(info)});
  }
  mcdata_com_release_send(dialog, parts, reason, action);
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 13.2.4.2.1, 13.2.4.2.3, 13.2.6.2.2.2 and 13.2.6.2.2.3: a SIP INFO request of the
// g.3gpp.mcdata-com-release Info-Package that the MCData server sends in the dialog of the communication.
SipResponse Client::mcdata_com_release_received(const SipMessage &request) {
  SipResponse response;
  response.status_code = 200;
  const auto parts = request.parts.empty() ? parse_body_parts(request.content_type, request.body) : request.parts;
  const auto reason = reason_text(std::string(get_header(request.headers, "Reason")));
  for (const auto &part : parts) {
    if (part.content_type == mcdata::payload_mime) {
      // TS 24.282 clause 13.2.6.2.2.2: the originator's remaining amount of data, for the authorised user.
      std::string remaining;
      for (const auto &item : mcdata::decode_data_payload(part.body).payloads) remaining += item.data;
      mcdata_release_["remaining"] = remaining;
      event("mcdata_release_remaining_received", {{"remaining", remaining}});
      return response;
    }
    if (part.content_type != mcdata::signalling_mime) continue;
    const auto message = mcdata::decode_communication_release(part.body);
    switch (message.type) {
    case mcdata::CommReleaseType::IntentToRelease: {
      mcdata_release_ = {{"state", "release-pending"}, {"reason", reason}, {"query", message.remaining_data_query}};
      if (message.remaining_data_query) {
        // TS 24.282 clause 13.2.4.2.1 step 2c: answered in a SIP INFO request of its own, as a TEXT payload.
        const auto remaining = std::to_string(mcdata_remaining_bytes());
        mcdata_release_["remaining_reported"] = remaining;
        schedule([this, dialog = request.dialog_id, remaining] {
          mcdata::DataPayload payload{{{mcdata::ContentType::Text, remaining}}};
          mcdata_com_release_send(dialog, {{mcdata::payload_mime, mcdata::encode(payload)}}, {}, "remaining");
        });
      }
      break;
    }
    case mcdata::CommReleaseType::ExtensionRequest:
      // TS 24.282 clause 13.2.6.2.2.3: the originator asks the authorised user for an extension.
      mcdata_release_["state"] = "extension-requested";
      break;
    case mcdata::CommReleaseType::ExtensionResponse:
      mcdata_release_["state"] =
          message.extension == mcdata::ExtensionResponse::Accepted ? "extended" : "extension-refused";
      break;
    default: response.status_code = 403; return response;
    }
    event("mcdata_com_release_received", {{"type", static_cast<int>(message.type)}, {"reason", reason}});
    return response;
  }
  response.status_code = 403;
  return response;
}

} // namespace mcx
