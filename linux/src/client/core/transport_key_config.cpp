//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The pre-provisioned TrK and InK of each MC service user from the local configuration (transport_key_config.hpp).
//
// Specifications: TS 33.180 V19.5.0 (Rel-19) clauses 5.3.2 and 5.3.3.
#include "transport_key_config.hpp"

#include <algorithm>
#include <stdexcept>

namespace mcx {
//------------------------------------------------------------------------------
// TS 33.180 clause 5.3.2: the entry of the MC service user's URI in security.transport_keys.
transport::TransportKeys load_transport_keys(const nlohmann::json &security, const std::string &service_user) {
  return transport::load_for(security, service_user);
}

//------------------------------------------------------------------------------
// Helper of the status: the entries of security.transport_keys whose URI is no identity of this client.
std::vector<std::string> unmatched_transport_keys(const nlohmann::json &security,
                                                  const std::vector<std::string> &identities) {
  std::vector<std::string> out;
  if (!security.is_object() || !security.contains("transport_keys") || !security.at("transport_keys").is_object())
    return out;
  for (const auto &entry : security.at("transport_keys").items())
    if (std::find(identities.begin(), identities.end(), entry.key()) == identities.end()) out.push_back(entry.key());
  return out;
}
} // namespace mcx
