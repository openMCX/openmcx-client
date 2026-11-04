//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The pre-provisioned TrK and InK of each MC service user from the local configuration
// (src/client/core/transport_key_config.hpp): each identity gets its own entry only, the InK before the TrK for
// integrity, the TrK alone when no InK is present, nothing without an entry.
//
// Specifications: TS 33.180 V19.5.0 (Rel-19) clauses 5.3.2 and 5.3.3, annexes D.2.2 and D.3.1.

#include "transport_key_config.hpp"

#include <unistd.h>

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <sys/stat.h>

namespace {
int failures = 0;

//------------------------------------------------------------------------------
// Helper: count and report a failed check.
void check(bool ok, const char *what) {
  if (ok) return;
  ++failures;
  std::fprintf(stderr, "FAILED: %s\n", what);
}

//------------------------------------------------------------------------------
// Helper: an owner-only key file holding the given hexadecimal text.
std::string key_file(const std::filesystem::path &dir, const std::string &name, const std::string &hex) {
  const auto path = dir / name;
  std::ofstream(path) << hex << "\n";
  ::chmod(path.c_str(), 0600);
  return path.string();
}

//------------------------------------------------------------------------------
// Helper: the error of a refused configuration, empty when it loads.
std::string refusal(const nlohmann::json &security, const std::string &user) {
  try {
    mcx::load_transport_keys(security, user);
  } catch (const std::exception &error) {
    return error.what();
  }
  return {};
}
} // namespace

int main() {
  const std::string mcptt = "sip:alice-mcptt@mcx.test", mcdata = "sip:alice-mcdata@mcx.test",
                    mcvideo = "sip:alice-mcvideo@mcx.test";
  const auto dir = std::filesystem::temp_directory_path() / ("mcx-transport-key-config-" + std::to_string(::getpid()));
  std::filesystem::create_directories(dir);
  const std::string trk_hex(64, 'a'), ink_hex(64, 'b'), data_trk_hex(64, 'c');
  const auto trk = key_file(dir, "trk.hex", trk_hex), ink = key_file(dir, "ink.hex", ink_hex),
             data_trk = key_file(dir, "data-trk.hex", data_trk_hex);
  const nlohmann::json security = {
      {"kms_uri", "https://kms.mcx.test"},
      {"transport_keys",
       {{mcptt, {{"trk", {{"file", trk}, {"id", 1}}}, {"ink", {{"file", ink}, {"id", 2}}}}},
        {mcdata, {{"trk", {{"file", data_trk}, {"id", 3}}}}}}}};

  // No configuration, no keys.
  auto none = mcx::load_transport_keys({{"kms_uri", "https://kms.mcx.test"}}, mcptt);
  check(!none.trk && !none.ink, "no security.transport_keys: no keys");
  check(!mcx::load_transport_keys(nlohmann::json(), mcptt).trk, "no security section: no keys");

  // Each identity its own entry; the InK for integrity when present, else the TrK.
  const auto ptt = mcx::load_transport_keys(security, mcptt);
  check(ptt.user == mcptt && ptt.trk && ptt.trk->id == 1 && ptt.ink && ptt.ink->id == 2, "MCPTT identity's keys");
  check(mcx::transport::integrity_xpk(ptt)->id == 2, "integrity with the InK when present");
  const auto data = mcx::load_transport_keys(security, mcdata);
  check(data.user == mcdata && data.trk && data.trk->id == 3 && !data.ink, "MCData identity's keys");
  check(mcx::transport::integrity_xpk(data)->id == 3, "integrity with the TrK when no InK is present");

  // No fallback to another identity's entry; the URI is matched exactly as configured.
  const auto video = mcx::load_transport_keys(security, mcvideo);
  check(!video.trk && !video.ink, "MCVideo identity without an entry: no keys, not another user's");
  check(!mcx::load_transport_keys(security, "sip:Alice-MCPTT@mcx.test").trk, "URI matched exactly");

  // An entry of no identity of the client is reported (a mistyped URI is otherwise silent).
  check(mcx::unmatched_transport_keys(security, {mcptt, mcdata}).empty(), "every entry matches an identity");
  check(mcx::unmatched_transport_keys(security, {mcptt}) == std::vector<std::string>{mcdata},
        "unmatched entry reported");
  check(mcx::unmatched_transport_keys(nlohmann::json(), {mcptt}).empty(), "no configuration, nothing unmatched");

  // Malformed configurations are refused, without the key material in the error.
  check(!refusal({{"transport_keys", "keys"}}, mcptt).empty(), "transport_keys must be an object");
  const auto bad = key_file(dir, "bad.hex", std::string(63, 'a') + "z");
  const auto error = refusal({{"transport_keys", {{mcptt, {{"trk", {{"file", bad}, {"id", 1}}}}}}}}, mcptt);
  check(!error.empty() && error.find("aaaa") == std::string::npos, "bad key text refused without its contents");
  check(!refusal({{"transport_keys", {{mcptt, {{"trk", {{"file", trk}}}}}}}}, mcptt).empty(),
        "key without its identifier refused");

  // security.kms_via_proxy: direct by default; through the proxy only with a proxy and a TrK for every identity.
  const std::string proxy = "https://proxy.mcx.test:8443";
  check(mcx::kms_proxy(security, proxy, {mcptt, mcdata}).empty(), "KMS direct by default");
  auto via = security;
  via["kms_via_proxy"] = true;
  check(mcx::kms_proxy(via, proxy, {mcptt, mcdata}) == proxy, "KMS through the proxy with TrKs for every identity");
  bool refused = false;
  try {
    (void)mcx::kms_proxy(via, "", {mcptt});
  } catch (const std::exception &) {
    refused = true;
  }
  check(refused, "KMS through the proxy refused without a proxy");
  refused = false;
  try {
    (void)mcx::kms_proxy(via, proxy, {mcptt, mcvideo});
  } catch (const std::exception &) {
    refused = true;
  }
  check(refused, "KMS through the proxy refused without a TrK for an identity (TS 33.180 clause 5.3.2)");
  via["kms_via_proxy"] = "yes";
  refused = false;
  try {
    (void)mcx::kms_proxy(via, proxy, {mcptt});
  } catch (const std::exception &) {
    refused = true;
  }
  check(refused, "kms_via_proxy must be a boolean");

  std::filesystem::remove_all(dir);
  if (failures == 0) std::printf("transport key configuration: ok\n");
  return failures == 0 ? 0 : 1;
}
