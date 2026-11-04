//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The pre-provisioned transport key (TrK) and integrity key (InK) of each MC service user of the client, from the local
// configuration (OpenMCX's provisioning format, not a wire format): "security.transport_keys", an object keyed by the
// exact MC service user URI whose values are the {"trk": {"file", "id"}, "ink": {"file", "id"}} of
// mcx::transport::load (mcx::transport::load_for). Checked here at start; the key management client reads them again
// for each KMS exchange (signed requests and responses, TrK-wrapped key sets: mcx/key_documents.hpp).
//
// Specifications: TS 33.180 V19.5.0 (Rel-19) clauses 5.3.2, 5.3.3 and 5.11.1, annex F.1.4.
#pragma once
#include "mcx/transport_keys.hpp"

#include <nlohmann/json.hpp>

#include <string>
#include <vector>

namespace mcx {
// TS 33.180 clause 5.3.2: the keys pre-provisioned for one MC service user (P0 of annex F.1.4, the URI as configured):
// the entry of exactly that URI, none without one; another user's entry is never used.
transport::TransportKeys load_transport_keys(const nlohmann::json &security, const std::string &service_user);

// The URIs of security.transport_keys that are none of the given MC service user identities (never used).
std::vector<std::string> unmatched_transport_keys(const nlohmann::json &security,
                                                  const std::vector<std::string> &identities);

// security.kms_via_proxy (false by default): the home HTTP proxy the KMS requests go through, empty for direct. TS
// 24.482 annex A.2.1.2 and TS 33.180 clause 5.3.2 leave open which applies (spec conflict, Known limitations); the
// option follows the ETSI test system for TS 37.579 (the test suite's reading, a labelled exception). Through the
// proxy, clause 5.3.2 says "for public safety users the TrK shall be used": refused (throws) without a proxy or without
// a TrK for each identity.
std::string kms_proxy(const nlohmann::json &security, const std::string &proxy,
                      const std::vector<std::string> &identities);
} // namespace mcx
