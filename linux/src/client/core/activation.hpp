// MCX Admin activation of the client's configuration from a one-time activation URL (OpenMCX, not 3GPP):
// the declarations of activation.cpp.
//
// Specifications: TS 24.482 V19.1.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).
#pragma once
#include "mcx/http.hpp"

#include <nlohmann/json.hpp>

#include <string>

namespace mcx {
// The configuration's address map ("address_map", from the activation package): "host:port" as the package names a
// server -> "address:port" where this client reaches it; empty without one.
HttpAddressMap address_map(const nlohmann::json &cfg);
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
