//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The gate of required XML protection, kept apart from the Client class so that tests/test_xml_gate.cpp can check it:
// whether a message's bodies fall under the confidentiality or integrity protection a service requires, and the
// refusal to send such a message without the service's CSK or past its Expiry time.
//
// Specifications: TS 24.379 V19.8.0 (Rel-19), TS 24.281 V19.7.0 (Rel-19), TS 24.282 V19.8.0 (Rel-19),
// TS 33.180 V19.5.0 (Rel-19).

#pragma once

#include "mcx/keying.hpp"
#include "mcx/sip.hpp"
#include "mcx/xml_security.hpp"

#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace mcx::xml_gate {

//------------------------------------------------------------------------------
// TS 24.379 clauses 6.6.2.3.1 and 6.6.3.3.1 (TS 24.281 the same clauses, TS 24.282 clauses 6.5.2.3.1 and 6.5.3.3.1):
// whether required protection applies to a message: a body the service encrypts while confidentiality protection is
// required, or an XML body while integrity protection is required (the requirement alone, whatever the keys).
template <class Encrypted, class Signed>
bool applies(const std::vector<BodyPart> &parts, bool confidentiality, bool integrity, Encrypted encrypted,
             Signed signable) {
  return std::any_of(parts.begin(), parts.end(), [&](const BodyPart &part) {
    return (confidentiality && encrypted(part.content_type)) || (integrity && signable(part.content_type));
  });
}

//------------------------------------------------------------------------------
// TS 33.180 annex E.6.10 and annex E.6.9: required protection is never dropped. Without the service's CSK, or once it
// is expired or revoked (keying::require_in_use), the message is not sent: throws, and the SIP engine refuses it.
inline void require_key(const keying::KeyMessage *csk, std::string_view service, std::uint64_t now) {
  if (!csk) throw std::runtime_error("XML protection required without the " + std::string(service) + " CSK");
  keying::require_in_use(*csk, now);
}

//------------------------------------------------------------------------------
// TS 24.484 clause 6.3.13.2.2 and TS 24.481 clauses 6.3.13.2.1 and 7.7: whether an initial SUBSCRIBE (Event
// xcap-diff) starts a configuration management, group document or GKTP subscription: its
// application/resource-lists+xml body lists configuration management documents (TS 24.484 clause 7: the
// org.3gpp.mcptt/mcvideo/mcdata application usages), group documents (org.openmobilealliance.groups) or the <GKTPs>
// of a GKTP document (org.3gpp.MCPTT-GKTP).
inline bool subscription_procedure(const std::vector<BodyPart> &parts) {
  static constexpr std::string_view auids[] = {"org.3gpp.mcptt.", "org.3gpp.mcvideo.", "org.3gpp.mcdata.",
                                               "org.openmobilealliance.groups/", "org.3gpp.MCPTT-GKTP/"};
  return std::any_of(parts.begin(), parts.end(), [](const BodyPart &part) {
    if (part.content_type != "application/resource-lists+xml") return false;
    for (std::size_t at = part.body.find("uri=\""); at != std::string::npos; at = part.body.find("uri=\"", at + 5)) {
      const auto end = part.body.find('"', at + 5);
      const std::string_view uri(part.body.data() + at + 5, (end == std::string::npos ? part.body.size() : end) - at - 5);
      if (std::any_of(std::begin(auids), std::end(auids),
                      [&uri](std::string_view auid) { return uri.find(auid) != std::string_view::npos; }))
        return true;
    }
    return false;
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.1.4 (TS 24.281 clause 6.6.1.4, TS 24.282 clause 6.5.1.4): a received SIP request whose
// signatures or encrypted XML elements name an XPK-ID (ds:KeyName) the receiver does not hold is refused. The XPK of
// clause 6.6.3.4.2 step 4 cannot be obtained, so the signature validation of XMLDSig clause 3.2.2 fails and step 5
// sends 403 with "139 integrity protection check failed"; the decryption of clause 6.6.2.4.2 step 2 (XML Encryption
// 1.1 clause 4.4 needs the key) fails and step 3 sends 403 with "140 unable to decrypt XML content". Neither clause
// names an unknown XPK-ID in so many words (spec text defect, reported), but no reading lets the request be processed
// unverified or still encrypted (clause 6.6.1.4 steps 1 b) and 2 b)). Throws with the warning code first, which the
// client's request dispatch answers with that 403.
// Not covered, as the specs give no outcome there (kept as they were and reported): a SIP response (the only failure
// action, a 403, cannot answer a response), encrypted URI attributes (clause 6.6.2.4.3 has no failure step) and a
// body without a ds:KeyName (TS 33.180 clauses 9.3.4.2 and 9.3.5: the key is then "known ... through other means").
// held(id): whether the receiver holds that XPK-ID; xml_part(type): a body type whose XML elements are encrypted.
template <class Held, class XmlPart>
void refuse_unknown_keys(const std::vector<BodyPart> &parts, std::string_view signed_mime, Held held,
                         XmlPart xml_part) {
  for (const auto &part : parts) {
    if (part.content_type == signed_mime) {
      if (const auto id = xmlsec::signature_key_id(part.body); id && !held(*id))
        throw std::runtime_error("139 signed under an XPK-ID this client does not hold");
    } else if (xml_part(part.content_type) && part.body.find("EncryptedData") != std::string::npos) {
      // The element half of xmlsec::is_protected(); xmlsec::key_id() names the first encrypted URI or element.
      if (const auto id = xmlsec::key_id(part.body); id && !held(*id))
        throw std::runtime_error("140 XML content encrypted under an XPK-ID this client does not hold");
    }
  }
}

} // namespace mcx::xml_gate
