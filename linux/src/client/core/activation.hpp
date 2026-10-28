//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCX Admin activation of the client's configuration from a one-time activation URL (OpenMCX, not 3GPP):
// the declarations of activation.cpp.
//
// Specifications: TS 24.482 V19.1.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).
#pragma once
#include "client_rules.hpp"
#include "mcx/http.hpp"

#include <nlohmann/json.hpp>

#include <string>

namespace mcx {
// TS 33.180 clause B.4.2.2: the IdMS settings of the authentication request: the configured oauth, with the scope of
// the configured services (client_rules::oidc_scope) unless oauth.scope names one.
inline nlohmann::json oauth_settings(const nlohmann::json &cfg) {
  auto oauth = cfg.at("oauth");
  if (!oauth.contains("scope"))
    oauth["scope"] = client_rules::oidc_scope(cfg.contains("mcdata"), cfg.contains("mcvideo"), cfg.contains("security"),
                                              cfg.contains("endpoints") && cfg.at("endpoints").contains("lms"));
  return oauth;
}

// The configuration's address map ("address_map", from the activation package): "host:port" as the package names a
// server -> "address:port" where this client reaches it; empty without one.
HttpAddressMap address_map(const nlohmann::json &cfg);
// TS 33.180 clause 6.1.1 (TS 33.310 clause 6.1.1): the configuration's CRL file ("tls.crl_file", PEM) that every
// HTTPS request checks the server's certificate chain against; empty without one.
std::string crl_file(const nlohmann::json &cfg);
// Whether a configuration was written by an activation.
bool activated(const nlohmann::json &cfg);
// The configuration completed from the activation URL's bootstrap package, the
// account's password and a new SIP credential.
// dir is where a device key and certificate the Credential Service issues are written,
// when the configuration names none (slice F2).
nlohmann::json activate(nlohmann::json cfg, const std::string &link, const std::string &password_file,
                        const std::string &dir = ".");
// Activates the configuration file at path unless it is activated already; before
// activation it holds local settings only, so it is read without validation.
// Returns whether it activated.
bool activate_file(const std::string &path, const std::string &url, const std::string &password_file);
// Writes a configuration in place (owner-only), replacing the file whole.
void save_config(const std::string &path, const nlohmann::json &cfg);
} // namespace mcx
