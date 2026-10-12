// MCX Admin activation of the client's configuration from a one-time activation URL (OpenMCX, not 3GPP).
//
// Specifications: TS 24.482 V19.1.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "activation.hpp"

#include "mcx/http.hpp"

#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>

#include <filesystem>
#include <fstream>
#include <memory>
#include <stdexcept>

namespace mcx {
namespace {

//------------------------------------------------------------------------------
// Helper: throw an activation error unless ok.
void require(bool ok, const std::string &what) {
  if (!ok) throw std::runtime_error("activation: " + what);
}

//------------------------------------------------------------------------------
// Helper: the first line of the password file.
std::string read_secret(const std::string &path) {
  std::ifstream in(path);
  require(static_cast<bool>(in), "cannot read the password file");
  std::string value;
  std::getline(in, value);
  require(!value.empty(), "the password file is empty");
  return value;
}

//------------------------------------------------------------------------------
// A device key made here (EC P-256) and a PKCS#10 request for it (slice F2): the
// Credential Service certifies the key only, so the request's subject is a hint.
struct DeviceKey {
  std::string key_pem, request_pem;
};

//------------------------------------------------------------------------------
// Helper: the text an OpenSSL memory BIO holds.
std::string bio_text(BIO *bio) {
  char *data = nullptr;
  const long length = BIO_get_mem_data(bio, &data);
  return std::string(data, static_cast<std::size_t>(length));
}

//------------------------------------------------------------------------------
// Helper: make a device key (EC P-256) and its PKCS#10 certificate request (slice F2).
DeviceKey make_device_key(const std::string &common_name) {
  std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)> key(EVP_EC_gen("P-256"), EVP_PKEY_free);
  require(key != nullptr, "cannot make a device key");
  std::unique_ptr<X509_REQ, decltype(&X509_REQ_free)> req(X509_REQ_new(), X509_REQ_free);
  X509_NAME *name = X509_REQ_get_subject_name(req.get());
  require(X509_REQ_set_version(req.get(), 0) == 1 &&
              X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_UTF8,
                                         reinterpret_cast<const unsigned char *>(common_name.c_str()), -1, -1,
                                         0) == 1 &&
              X509_REQ_set_pubkey(req.get(), key.get()) == 1 && X509_REQ_sign(req.get(), key.get(), EVP_sha256()) > 0,
          "cannot make a certificate request");
  std::unique_ptr<BIO, decltype(&BIO_free)> key_bio(BIO_new(BIO_s_mem()), BIO_free),
      req_bio(BIO_new(BIO_s_mem()), BIO_free);
  require(PEM_write_bio_PrivateKey(key_bio.get(), key.get(), nullptr, nullptr, 0, nullptr, nullptr) == 1 &&
              PEM_write_bio_X509_REQ(req_bio.get(), req.get()) == 1,
          "cannot write the device key");
  return {bio_text(key_bio.get()), bio_text(req_bio.get())};
}

//------------------------------------------------------------------------------
// Helper: write a file readable by its owner only (secret) or by everyone.
void write_private(const std::filesystem::path &path, const std::string &text, bool secret) {
  {
    std::ofstream out(path, std::ios::trunc);
    require(static_cast<bool>(out), "cannot write " + path.string());
    out << text;
  }
  using std::filesystem::perms;
  std::filesystem::permissions(path, secret ? perms::owner_read | perms::owner_write
                                            : perms::owner_read | perms::owner_write | perms::group_read |
                                                  perms::others_read);
}

//------------------------------------------------------------------------------
// Helper: whether the configuration names a device certificate and key that exist.
bool has_device_certificate(const nlohmann::json &cfg) {
  const auto tls = cfg.value("tls", nlohmann::json::object());
  return tls.contains("cert_file") && tls.contains("key_file") &&
         std::filesystem::is_regular_file(tls.at("cert_file").get<std::string>()) &&
         std::filesystem::is_regular_file(tls.at("key_file").get<std::string>());
}

} // namespace

//------------------------------------------------------------------------------
// The configuration's address map ("address_map", from the activation package): "host:port" -> "address:port".
HttpAddressMap address_map(const nlohmann::json &cfg) {
  HttpAddressMap map;
  // A named copy: items() of the temporary value() would outlive it in the loop.
  const auto entries = cfg.value("address_map", nlohmann::json::object());
  for (const auto &[named, reached] : entries.items()) map.emplace(named, reached.get<std::string>());
  return map;
}

//------------------------------------------------------------------------------
// Helper: whether the configuration has been activated already.
bool activated(const nlohmann::json &cfg) { return cfg.value("activated", false); }

//------------------------------------------------------------------------------
// MCX Admin activation: the client
// fetches its bootstrap package from the one-time activation URL, signs in at IdMS
// with the account's password (TS 24.482 clause 6.2.1), obtains its SIP credential from the Credential
// Service, and writes the result into its configuration, which keeps its local
// settings (ports, paths, the device's TLS files). An activated configuration is
// marked, so that a restart does not use the one-time URL again.
nlohmann::json activate(nlohmann::json cfg, const std::string &link, const std::string &password_file,
                        const std::string &dir) {
  require(link.rfind("https://", 0) == 0, "the activation URL must be https");
  // The link's #pin= names MCX Admin's certificate, so a device that does not trust the system CA yet (a dispatcher
  // or a client on another machine, reaching MCX Admin at an address its certificate does not name) fetches the
  // package from that server and no other; a link without a pin is checked against the configured CA.
  const auto hash = link.find('#');
  const std::string url = link.substr(0, hash);
  std::string pin;
  if (hash != std::string::npos) {
    const std::string fragment = link.substr(hash + 1);
    for (std::size_t at = 0; at <= fragment.size();) {
      const auto end = std::min(fragment.find('&', at), fragment.size());
      if (fragment.compare(at, 4, "pin=") == 0) pin = fragment.substr(at + 4, end - at - 4);
      at = end + 1;
    }
  }
  const auto configured_ca = cfg.value("tls", nlohmann::json::object()).value("ca_file", std::string());
  require(!pin.empty() || !configured_ca.empty(), "the link has no #pin= and no CA file is configured");
  const auto fetched = pin.empty() ? HttpClient(configured_ca).request("GET", url) : http_get_pinned(url, pin);
  require(fetched.status == 200,
          "the activation URL was refused (" + std::to_string(fetched.status) + "): used, expired or unknown");
  const auto boot = fetched.json();
  require(boot.value("format", "") == "openmcx-activation-1", "unknown bootstrap format");
  // The system's trust root from the package: the client then needs no CA file of its own.
  if (boot.contains("trust_root") && !dir.empty()) {
    const auto root = std::filesystem::path(dir) / "trust-root.pem";
    write_private(root, boot.at("trust_root").get<std::string>(), false);
    cfg["tls"]["ca_file"] = root.string();
  }
  require(!cfg.value("tls", nlohmann::json::object()).value("ca_file", std::string()).empty(),
          "the package has no trust root and no CA file is configured");
  // The package's address map: where this client reaches each server the package names (through a host's published
  // ports); every HTTPS request of the client applies it.
  if (boot.contains("address_map")) cfg["address_map"] = boot.at("address_map");
  HttpClient http(cfg.at("tls").at("ca_file").get<std::string>(), {}, address_map(cfg));
  // Identity and system settings from the bootstrap; local settings stay.
  const auto &idms = boot.at("idms");
  cfg["oauth"] = {{"issuer", idms.at("issuer")},
                  {"client_id", idms.at("client_id")},
                  {"redirect_uri", idms.at("redirect_uri")},
                  {"username", boot.at("login")},
                  {"password", read_secret(password_file)}};
  cfg["sip"]["core_uri"] = boot.at("sip").at("core_uri");
  cfg["sip"]["identity"] = boot.at("sip").at("identity");
  for (const auto *service : {"mcptt", "mcdata", "mcvideo"}) {
    if (!boot.contains(service)) continue;
    auto merged = cfg.value(service, nlohmann::json::object());
    merged.update(boot.at(service));
    cfg[service] = merged;
  }
  if (boot.contains("endpoints")) cfg["endpoints"] = boot.at("endpoints");
  // Sign in (TS 24.482 clause 6.2.1) and ask the Credential Service for the SIP credential.
  const auto tokens = oidc_login(http, cfg.at("oauth"));
  const auto token = tokens.at("access_token").get<std::string>();
  const auto issued = http.request("POST", boot.at("credentials_url").get<std::string>() + "/v1/sip-credential", {},
                                   {{"Authorization", "Bearer " + token}});
  require(issued.status == 200,
          "the Credential Service refused the SIP credential (" + std::to_string(issued.status) + ")");
  const auto credential = issued.json();
  cfg["sip"]["username"] = credential.at("username");
  cfg["sip"]["password"] = credential.at("password");
  // Slice F2: a configuration without a device certificate gets one for a key made
  // here, which never leaves this device.
  if (!has_device_certificate(cfg) && boot.contains("certificate_url")) {
    const auto device = make_device_key("mcx-device");
    const auto certified = http.request("POST", boot.at("certificate_url").get<std::string>(), device.request_pem,
                                        {{"Authorization", "Bearer " + token}, {"Content-Type", "application/pkcs10"}});
    require(certified.status == 200,
            "the Credential Service refused the device certificate (" + std::to_string(certified.status) + ")");
    const std::filesystem::path base(dir);
    write_private(base / "device.key", device.key_pem, true);
    write_private(base / "device.crt", certified.json().at("certificate").get<std::string>(), false);
    cfg["tls"]["key_file"] = (base / "device.key").string();
    cfg["tls"]["cert_file"] = (base / "device.crt").string();
  }
  // TS 33.180 annex B.5: an IdMS that issues a refresh token lets the device keep that token,
  // owner-only, instead of the user's password.
  if (tokens.contains("refresh_token")) {
    const std::filesystem::path base(dir);
    write_private(base / "idms-session.json",
                  nlohmann::json{{"refresh_token", tokens.at("refresh_token")}, {"nonce", tokens.at("nonce")}}.dump() +
                      "\n",
                  true);
    cfg["oauth"]["refresh_token_file"] = (base / "idms-session.json").string();
    cfg["oauth"].erase("password");
  }
  cfg["activated"] = true;
  return cfg;
}

//------------------------------------------------------------------------------
// Helper: activate the configuration file once; false when it was activated already.
bool activate_file(const std::string &path, const std::string &url, const std::string &password_file) {
  std::ifstream in(path);
  require(static_cast<bool>(in), "cannot read the configuration");
  const auto cfg = nlohmann::json::parse(in);
  if (activated(cfg)) return false;
  save_config(path, activate(cfg, url, password_file, std::filesystem::path(path).parent_path().string()));
  return true;
}

//------------------------------------------------------------------------------
// Helper: replace the configuration file atomically, readable by its owner only.
void save_config(const std::string &path, const nlohmann::json &cfg) {
  const auto tmp = path + ".tmp";
  {
    std::ofstream out(tmp, std::ios::trunc);
    require(static_cast<bool>(out), "cannot write the configuration");
    out << cfg.dump(2) << '\n';
  }
  std::filesystem::permissions(tmp, std::filesystem::perms::owner_read | std::filesystem::perms::owner_write);
  std::filesystem::rename(tmp, path);
}

} // namespace mcx
