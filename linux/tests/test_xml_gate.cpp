//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The gate of required XML protection (src/client/core/xml_gate.hpp): a message falls under it by the requirement
// alone (confidentiality-only, integrity-only, both, none), and it is not sent without the service's CSK or past the
// CSK's Expiry time or once revoked (TS 33.180 annexes E.6.9 and E.6.10); a received request naming an XPK-ID the
// receiver does not hold is refused with 139 or 140 (TS 24.379 clauses 6.6.3.4.2 and 6.6.2.4.2).
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

//------------------------------------------------------------------------------
// Helper: the refusal of a received request's bodies (xml_gate::refuse_unknown_keys) when only XPK-ID 7 is held; ""
// when accepted, else the warning code (the first three characters of the reason).
std::string unknown_key_refusal(const std::vector<mcx::BodyPart> &parts) {
  try {
    mcx::xml_gate::refuse_unknown_keys(
        parts, "application/vnd.3gpp.mcptt-signed+xml", [](std::uint32_t id) { return id == 7; },
        [](const std::string &type) { return type == "application/vnd.3gpp.mcptt-info+xml"; });
  } catch (const std::exception &error) {
    return std::string(error.what()).substr(0, 3);
  }
  return "";
}

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

  // A received request naming an XPK-ID not held: signatures 139, encrypted elements 140; the held XPK-ID passes (its
  // verification and decryption follow in the client); a body without protection or without a ds:KeyName passes.
  {
    mcx::xmlsec::Key held, unknown;
    held.id = 7;
    unknown.id = 9;
    held.user = unknown.user = "sip:alice@mcx.test";
    held.xpk.fill(1);
    unknown.xpk.fill(2);
    const std::string info = "<mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"><mcptt-Params><mcptt-request-uri type=\"Normal\">"
                             "<mcpttURI>sip:group@mcx.test</mcpttURI></mcptt-request-uri></mcptt-Params></mcpttinfo>";
    const auto signed_parts = [&info](const mcx::xmlsec::Key &key) {
      return std::vector<mcx::BodyPart>{
          {"application/vnd.3gpp.mcptt-info+xml", info, "a@mcx"},
          {"application/vnd.3gpp.mcptt-signed+xml",
           mcx::xmlsec::sign({{"a@mcx", info}}, key, "urn:3gpp:ns:mcpttSignedDoc:1.0")}};
    };
    const auto encrypted_parts = [&info](const mcx::xmlsec::Key &key) {
      return std::vector<mcx::BodyPart>{{"application/vnd.3gpp.mcptt-info+xml",
                                         mcx::xmlsec::encrypt(info, key, mcx::xmlsec::signalling_profile())}};
    };
    check(unknown_key_refusal(signed_parts(unknown)) == "139", "signature under an unknown XPK-ID: 139");
    check(unknown_key_refusal(signed_parts(held)).empty(), "signature under the held XPK-ID passes");
    check(unknown_key_refusal(encrypted_parts(unknown)) == "140", "element under an unknown XPK-ID: 140");
    check(unknown_key_refusal(encrypted_parts(held)).empty(), "element under the held XPK-ID passes");
    check(unknown_key_refusal({{"application/vnd.3gpp.mcptt-info+xml", info}}).empty(), "plain body passes");
    auto no_name = encrypted_parts(unknown);
    const auto from = no_name[0].body.find("<ds:KeyInfo"), to = no_name[0].body.find("</ds:KeyInfo>");
    check(from != std::string::npos && to != std::string::npos, "test document has a ds:KeyInfo");
    if (from != std::string::npos && to != std::string::npos) {
      no_name[0].body.erase(from, to + 13 - from);
      check(unknown_key_refusal(no_name).empty(), "element without a ds:KeyName: left as before");
    }
  }

  if (failures) return 1;
  std::puts("xml gate: ok");
  return 0;
}
