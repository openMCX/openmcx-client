// Unit tests of src/client/core/client_rules.hpp: the rules of the MC client that need no client state.
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// TS 24.484 V19.6.0 (Rel-19), RFC 3261, RFC 3986, RFC 6749.

#include "client_rules.hpp"

#include <cstdio>
#include <optional>
#include <string>
#include <vector>

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
// The part of an SDS or FD NOTIFICATION message the notifier rule reads.
struct Notice {
  std::optional<std::string> sender;
};

//------------------------------------------------------------------------------
// TS 24.282 clauses 15.1.5, 15.1.6 and 12.2.3 step 15c: the notifier of disposition notifications.
void test_notifiers() {
  using mcx::client_rules::notifiers;
  const std::vector<Notice> single{{std::nullopt}};
  check(notifiers(single, "alice", "") == std::vector<std::string>{"alice"}, "a single notice names the calling user");
  check(!notifiers(single, "", ""), "a single notice without calling user or Sender IE is refused");
  check(notifiers(std::vector<Notice>{{"bob"}}, "", "") == std::vector<std::string>{"bob"},
        "a single notice without calling user names its Sender IE");
  const std::vector<Notice> aggregated{{"bob"}, {"carol"}};
  check(notifiers(aggregated, "alice", "group") == std::vector<std::string>{"bob", "carol"},
        "an aggregated notification names each Sender IE, not the calling user");
  check(!notifiers(aggregated, "alice", ""), "an aggregated notification not from a group is refused");
  // The crash of the finding: aggregated, calling user present, one Sender IE missing.
  check(!notifiers(std::vector<Notice>{{"bob"}, {std::nullopt}}, "alice", "group"),
        "an aggregated notification with a notice without Sender IE is refused");
  check(!notifiers(std::vector<Notice>{{"bob"}, {""}}, "", "group"), "an empty Sender IE is refused");
}

//------------------------------------------------------------------------------
// RFC 3261 section 20.43 with TS 24.379 clause 4.4.1: the MC warning codes of a Warning header field.
void test_has_warning() {
  using mcx::client_rules::has_warning;
  check(has_warning("399 mcdata \"149 SIP INFO request pending\"", 149), "149 in a 399 warn-text");
  check(has_warning("149 mcx \"SIP INFO request pending\"", 149), "149 as the warn-code");
  check(!has_warning("399 mcx \"110 user declined the call invitation\"", 149), "110 is not 149");
  check(!has_warning("399 mcx \"163 user 149 is busy\"", 149), "149 inside the text is not the code");
  check(!has_warning("399 host149 \"1490 other\"", 149), "1490 is not 149");
  check(has_warning("399 a \"110 declined\", 399 b \"175 call is forwarded\"", 175), "the second warning-value");
  check(has_warning("399 a \"x \\\"149 y\\\"\", 399 b \"149 z\"", 149), "escaped quotes skipped");
  check(!has_warning("", 149), "an empty header");
  check(!has_warning("399 mcx \"unterminated", 149), "an unterminated warn-text");
}

//------------------------------------------------------------------------------
// TS 33.180 annex B.5 with RFC 6749 section 5.2: when a refused refresh token is dropped.
void test_refresh_refusal() {
  using mcx::client_rules::refresh_refusal_final;
  check(!refresh_refusal_final(1, false, 100, 900), "a first refusal while the token is valid is retried");
  check(refresh_refusal_final(2, false, 130, 900), "a second refusal in a row is final");
  check(refresh_refusal_final(1, false, 900, 900), "a refusal once the token expired is final");
  check(refresh_refusal_final(1, true, 100, 900), "a client with a password signs in again at once");
}

//------------------------------------------------------------------------------
// RFC 3986 section 2.1: percent-decoding without throwing on a bad escape.
void test_percent_decode() {
  using mcx::client_rules::percent_decode;
  check(percent_decode("sip%3Agroup%40example.com") == "sip:group@example.com", "escapes decoded");
  check(percent_decode("a%zzb") == "a%zzb", "a bad escape kept");
  check(percent_decode("ab%4") == "ab%4", "a truncated escape kept");
  check(percent_decode("%41") == "A", "an escape at the end");
  check(percent_decode("%") == "%", "a lone percent");
}

//------------------------------------------------------------------------------
// TS 24.484 clause 8.3.2: the groups of an MCPTT user profile (<MCPTTGroupInfo> uri-entries).
void test_profile_groups() {
  using mcx::client_rules::parse_profile_groups;
  using Groups = std::vector<std::string>;
  const std::string profile = "<mcptt-user-profile><ruleset><cp:rule><uri-entry>sip:not-a-group@x</uri-entry>"
                              "</cp:rule></ruleset><OnNetwork><MCPTTGroupInfo><entry><uri-entry>sip:g1@x</uri-entry>"
                              "<display-name>One</display-name></entry><entry><uri-entry>sip:g2@x</uri-entry></entry>"
                              "</MCPTTGroupInfo><ImplicitAffiliations><entry><uri-entry>sip:g3@x</uri-entry></entry>"
                              "</ImplicitAffiliations></OnNetwork></mcptt-user-profile>";
  check(parse_profile_groups(profile) == Groups{"sip:g1@x", "sip:g2@x"}, "only the MCPTTGroupInfo entries");
  check(parse_profile_groups("<p:MCPTTGroupInfo><p:entry><p:uri-entry>sip:g@x</p:uri-entry></p:entry>"
                             "</p:MCPTTGroupInfo>") == Groups{"sip:g@x"},
        "a namespace prefix");
  check(parse_profile_groups("<MCPTTGroupInfo><entry><uri-entry>sip:a&amp;b@x</uri-entry></entry><entry>"
                             "<uri-entry>sip:a&amp;b@x</uri-entry></entry><entry><uri-entry/></entry>"
                             "<entry><uri-entry></uri-entry></entry></MCPTTGroupInfo>") == Groups{"sip:a&b@x"},
        "entities, duplicates and empty entries");
  check(parse_profile_groups("<MCPTTGroupInfo><entry><uri-entry>sip:g@x</uri-entry>").empty(),
        "an unterminated element");
  check(parse_profile_groups("<other>MCPTTGroupInfo</other>").empty(), "the name in text is not an element");
  check(parse_profile_groups("<MCPTTGroupInfo><entry><uri-entry>sip:1@x</uri-entry></entry></MCPTTGroupInfo>"
                             "<MCPTTGroupInfo><entry><uri-entry>sip:2@x</uri-entry></entry></MCPTTGroupInfo>") ==
            Groups{"sip:1@x", "sip:2@x"},
        "several MCPTTGroupInfo elements");
  std::string many = "<MCPTTGroupInfo>";
  for (int index = 0; index < 300; ++index)
    many += "<entry><uri-entry>sip:g" + std::to_string(index) + "@x</uri-entry></entry>";
  check(parse_profile_groups(many + "</MCPTTGroupInfo>").size() == 256, "at most 256 groups");
}

//------------------------------------------------------------------------------
// TS 24.380 table 8.2.2.1-1: floor message names; the received-audio bound drops whole seconds past the limit.
void test_floor_and_audio() {
  using mcx::client_rules::audio_overflow;
  using mcx::client_rules::floor_message_name;
  check(floor_message_name(6) == "Floor Revoke", "Floor Revoke named");
  check(floor_message_name(5) == "Floor Idle", "Floor Idle named");
  check(floor_message_name(7) == "unknown", "an unassigned type");
  check(audio_overflow(100, 100, 16) == 0, "at the limit nothing is dropped");
  check(audio_overflow(116, 100, 16) == 0, "within a second past the limit nothing is dropped");
  check(audio_overflow(117, 100, 16) == 17, "past a second the excess is dropped");
}

} // namespace

int main() {
  test_notifiers();
  test_has_warning();
  test_refresh_refusal();
  test_percent_decode();
  test_profile_groups();
  test_floor_and_audio();
  if (failures) std::fprintf(stderr, "%d check(s) failed\n", failures);
  return failures ? 1 : 0;
}
