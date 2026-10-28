//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Incoming SIP requests: refuse XML that failed protection checks, take CSK key downloads, open MCData signalling
// protection, then dispatch.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19), TS 33.180
// V19.5.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.5, TS 24.281 clause 7.2.5 and TS 24.282 clause 7.2.5 with TS 33.180 clauses 5.8 and 9.2.1.4:
// a SIP MESSAGE with the service's ICSI in P-Asserted-Service and an application/mikey body whose CSB-ID is a CSK-ID
// (four most significant bits '2') is a CSK key download. The I_MESSAGE is opened with this client's key material for
// that service (TS 33.180 clause 5.8.1: the key is "encrypted specifically to the MC user"), its initiator (IDRi)
// being the public service identity of the participating function (step 1 a) i)) and its signature verified with
// that identity (steps 1 c) and d)); any failure is answered 488 with warning 136. On success the CSK and CSK-ID
// replace those held for the participating function (step 2; TS 33.180 clause 9.2.1.4: "discard any previous CSKs
// ... and use the new CSK for uplink signaling") and the answer is 200 (OK) (TS 33.180 clause 5.8.2 step 2).
// Spec defect (reported): step 1 e) says to decrypt "using the participating MCPTT function's (KMS provisioned) UID
// key"; the key is encrypted to the MC user (TS 33.180 clause 5.8.1), so this client's own key is used. Not done: the
// IDRi of type UID (identity hiding, step 1 b)), which the MIKEY library does not open, and the MCData "signalling
// algorithm" (TS 24.282 clause 7.2.5 step 1 f)), whose place in the I_MESSAGE no specification defines (TS 33.180
// clause 8.5.4.1 gives no code point or IEI); the MCData algorithm stays DP_AES_128_GCM.
std::optional<SipResponse> Client::csk_key_download(const SipMessage &message) {
  if (message.method != "MESSAGE") return std::nullopt;
  const auto asserted = std::string(get_header(message.headers, "P-Asserted-Service"));
  const BodyPart *mikey = nullptr;
  const auto parts = message.parts.empty() && !message.body.empty()
                         ? std::vector<BodyPart>{{message.content_type.substr(0, message.content_type.find(';')),
                                                  message.body}}
                         : message.parts;
  for (const auto &part : parts)
    if (part.content_type == "application/mikey") mikey = &part;
  if (!mikey) return std::nullopt;
  try {
    if ((KeyClient::key_id(mikey->body) >> 28) != 2) return std::nullopt;
  } catch (const std::exception &) {
    return std::nullopt; // not a MIKEY message this procedure reads
  }
  KeyClient *keys = nullptr;
  std::string function, name;
  if (asserted == mcptt_icsi) {
    keys = keys_.get(), function = service(), name = "mcptt";
  } else if (mcvideo_enabled() && asserted == mcvideo_service.icsi) {
    keys = mcvideo_keys_.get(), function = mcvideo_service_uri(), name = "mcvideo";
  } else if (mcdata_enabled() && asserted == mcdata::icsi) {
    keys = mcdata_keys_.get(), function = mcdata_service(), name = "mcdata";
  } else {
    return std::nullopt;
  }
  SipResponse response;
  try {
    if (!keys) throw std::runtime_error("no key material for the service");
    const auto csk = keys->accept_csk(function, mikey->body);
    // RFC 3711 section 3.4: the floor (transmission) control streams of the calls kept go on under the new CSK
    // with their SRTCP indexes (mcptt_floor_streams, mcvideo_floor_streams, ProtectedCall::set_floor).
    if (name == "mcptt") {
      protection_.set_floor(csk, mcptt_floor_streams());
      csk_ = csk;
      status_["csk_id"] = csk.csb_id;
    } else if (name == "mcvideo") {
      mcvideo_protection_.set_floor(csk, mcvideo_floor_streams());
      mcvideo_csk_ = csk;
      status_["mcvideo_csk_id"] = csk.csb_id;
    } else {
      mcdata_csk_ = csk;
      mcdata_csk_accepted_ = true;
    }
    event("csk_key_download", {{"service", name}, {"csk_id", csk.csb_id}});
    response.status_code = 200;
  } catch (const std::exception &error) {
    event("csk_key_download_refused", {{"service", name}, {"reason", error.what()}});
    response.status_code = 488;
    response.headers = {{"Warning", "399 mcx \"136 authentication of the MIKEY-SAKKE I_MESSAGE failed\""}};
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.1.4 and TS 24.282 clause 6.5.1.4: verify and decrypt the XML of a received SIP request before
// any other procedure; MCData signalling payloads from the MCData server are opened first (TS 33.180 clause 9.5.3).
// The clear request then goes to incoming_clear().
SipResponse Client::incoming(const SipMessage &message) {
  // TS 24.379 clause 6.6.3.4.2 and clause 6.6.2.4.2: content that fails the integrity check or cannot be decrypted
  // is refused with 403 and Warning 139 or 140.
  if (!message.protection_error.empty()) {
    ++xml_refused_;
    event("xml_refused", {{"method", message.method}, {"reason", message.protection_error}});
    SipResponse response;
    response.status_code = 403;
    response.headers = {{"Warning", message.protection_error.starts_with("139")
                                        ? "399 mcx \"139 integrity protection check failed\""
                                        : "399 mcx \"140 unable to decrypt XML content\""}};
    return response;
  }
  if (auto answered = csk_key_download(message)) return *answered;
  // The CSK being uploaded counts as well: the server's first protected request can overtake its 200 (OK) to the
  // service authorisation (see mcdata_decrypt_xml()).
  if ((!mcdata_signalling_key() && !mcdata_xml_key(true)) || message.from_uri != mcdata_service() ||
      (message.body.empty() && message.parts.empty()))
    return incoming_clear(message);
  auto opened = message;
  try {
    auto parts = message.parts.empty() ? parse_body_parts(message.content_type, message.body) : message.parts;
    if (mcdata_signalling_key()) parts = mcdata_open_signalling(std::move(parts));
    try {
      parts = mcdata_decrypt_xml(std::move(parts));
    } catch (const std::exception &error) {
      // TS 24.282 clause 6.5.2.4.2 steps 1 and 3.
      ++mcdata_signalling_refused_;
      event("mcdata_xml_refused", {{"method", message.method}, {"reason", error.what()}});
      SipResponse response;
      response.status_code = 403;
      response.headers = {{"Warning", "399 mcx \"140 unable to decrypt XML content\""}};
      return response;
    }
    opened.parts = std::move(parts);
    if (message.parts.empty() && opened.parts.size() == 1) opened.body = opened.parts.front().body;
  } catch (const std::exception &error) {
    ++mcdata_signalling_refused_;
    event("mcdata_signalling_refused", {{"method", message.method}, {"reason", error.what()}});
    SipResponse response;
    response.status_code = 403;
    return response;
  }
  return incoming_clear(opened);
}

} // namespace mcx
