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

} // namespace mcx::xml_gate
