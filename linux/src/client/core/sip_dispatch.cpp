// Incoming SIP requests: refuse XML that failed protection checks, open MCData signalling protection, then dispatch.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

namespace mcx {

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
  if ((!mcdata_signalling_key() && !mcdata_xml_key()) || message.from_uri != mcdata_service() ||
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
