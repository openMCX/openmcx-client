//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The gate of required XML protection (src/client/core/xml_gate.hpp): a message falls under it by the requirement
// alone (confidentiality-only, integrity-only, both, none), and it is not sent without the service's CSK or past the
// CSK's Expiry time or once revoked (TS 33.180 annexes E.6.9 and E.6.10).
//
// Specifications: TS 24.379 V19.8.0 (Rel-19), TS 24.282 V19.8.0 (Rel-19), TS 33.180 V19.5.0 (Rel-19).

#include "xml_gate.hpp"

#include <cstdio>
#include <string>

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
// Helper: whether the gate refuses (throws).
bool refuses(const mcx::keying::KeyMessage *csk, std::uint64_t now) {
  try {
    mcx::xml_gate::require_key(csk, "MCPTT", now);
  } catch (const std::exception &) {
    return true;
  }
  return false;
}

bool info_part(const std::string &type) { return type == "application/vnd.3gpp.mcptt-info+xml"; }

bool xml_part(const std::string &type) { return type.ends_with("+xml"); }
} // namespace

int main() {
  using mcx::xml_gate::applies;
  const std::vector<mcx::BodyPart> publish{{"application/vnd.3gpp.mcptt-info+xml", "<mcpttinfo/>"},
                                           {"application/poc-settings+xml", "<poc-settings/>"}};
  const std::vector<mcx::BodyPart> settings_only{{"application/poc-settings+xml", "<poc-settings/>"}};
  const std::vector<mcx::BodyPart> sdp{{"application/sdp", "v=0"}};
  // The requirement alone decides.
  check(applies(publish, true, false, info_part, xml_part), "confidentiality-only applies to mcptt-info");
  check(!applies(settings_only, true, false, info_part, xml_part), "confidentiality-only: no encrypted body");
  check(applies(settings_only, false, true, info_part, xml_part), "integrity-only applies to every XML body");
  check(!applies(sdp, true, true, info_part, xml_part), "no XML body: not under the gate");
  check(!applies(publish, false, false, info_part, xml_part), "nothing required: not under the gate");

  // The key: missing, in use, expired, revoked; without Key Parameters there is no Expiry time.
  constexpr std::uint64_t now = 2000000000;
  check(refuses(nullptr, now), "missing CSK refused");
  mcx::keying::KeyMessage csk;
  check(!refuses(&csk, now), "CSK without Key Parameters accepted");
  mcx::keying::KeyProperties properties{};
  properties.status = 1;
  properties.expiry_unix_seconds = now + 60;
  csk.properties = properties;
  check(!refuses(&csk, now), "CSK in use accepted");
  check(refuses(&csk, now + 60), "expired CSK refused");
  csk.properties->expiry_unix_seconds = 0;
  check(!refuses(&csk, now), "Expiry time 0: never expires");
  csk.properties->status = 0;
  check(refuses(&csk, now), "revoked CSK refused");

  // The subscription procedures, told by the documents of the resource list.
  const auto list = [](const std::string &uri) {
    return std::vector<mcx::BodyPart>{{"application/vnd.3gpp.mcptt-info+xml", "<mcpttinfo/>"},
                                      {"application/resource-lists+xml",
                                       "<resource-lists><list><entry uri=\"" + uri + "\"/></list></resource-lists>"}};
  };
  check(mcx::xml_gate::subscription_procedure(list("org.3gpp.mcptt.user-profile/users/x/p.xml")),
        "configuration management subscription");
  check(mcx::xml_gate::subscription_procedure(list("https://gms/xcap-root/org.openmobilealliance.groups/global/"
                                                   "byGroupID/sip%3Ag")),
        "group document subscription (absolute XCAP URI)");
  check(mcx::xml_gate::subscription_procedure(list("org.3gpp.MCPTT-GKTP/global/byGroupID/g/~~/group")),
        "GKTP subscription");
  check(!mcx::xml_gate::subscription_procedure(list("sip:alice@mcx.test")), "a resource list of users: none");
  check(!mcx::xml_gate::subscription_procedure(publish), "no resource list: none");

  if (failures) return 1;
  std::puts("xml gate: ok");
  return 0;
}
