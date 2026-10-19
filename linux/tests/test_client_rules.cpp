//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Unit tests of src/client/core/client_rules.hpp: the rules of the MC client that need no client state.
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// TS 24.484 V19.6.0 (Rel-19), RFC 3261, RFC 3903, RFC 3986, RFC 6749.

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
// TS 33.180 table B.4.2.2-1: the scopes follow the configured services, key management only with keys.
void test_oidc_scope() {
  using mcx::client_rules::oidc_scope;
  check(oidc_scope(false, false, false) ==
            "openid 3gpp:mc:ptt_service 3gpp:mc:ptt_config_management_service 3gpp:mc:ptt_group_management_service",
        "MCPTT alone");
  const auto all = oidc_scope(true, true, true);
  for (const char *scope : {"3gpp:mc:data_service", "3gpp:mc:video_service", "3gpp:mc:ptt_key_management_service",
                            "3gpp:mc:data_key_management_service", "3gpp:mc:video_key_management_service",
                            "3gpp:mc:video_group_management_service"})
    check(all.find(scope) != std::string::npos, scope);
  check(oidc_scope(true, false, false).find("video") == std::string::npos, "no MCVideo scope without MCVideo");
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

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.2 with RFC 3261 section 20.33: when a refused initial service authorisation is published again.
void test_authorization_retry() {
  using mcx::client_rules::authorization_retry_delay_ms;
  check(authorization_retry_delay_ms(403, 0) == 5000u, "a 403 is retried after 5 s");
  check(authorization_retry_delay_ms(403, 1) == 10000u, "the second retry after 10 s");
  check(authorization_retry_delay_ms(403, 2) == 20000u, "the third retry after 20 s");
  check(authorization_retry_delay_ms(403, 3) == 60000u, "then every 60 s");
  check(authorization_retry_delay_ms(408, 50) == 60000u, "a timed-out transaction is retried, at most every 60 s");
  check(authorization_retry_delay_ms(480, 0) == 5000u, "a 480 is retried");
  check(authorization_retry_delay_ms(486, 3) == 60000u, "a 486 (another client authorised) is retried");
  check(authorization_retry_delay_ms(503, 0, "30") == 30000u, "a 503 honours Retry-After");
  check(authorization_retry_delay_ms(500, 4, " 2 (busy)") == 2000u, "a 500 honours Retry-After with a comment");
  check(authorization_retry_delay_ms(503, 0, "0") == 1000u, "a Retry-After of 0 waits 1 s");
  check(authorization_retry_delay_ms(503, 0, "99999") == 600000u, "a long Retry-After is bounded to 10 minutes");
  check(authorization_retry_delay_ms(503, 1, "soon") == 10000u, "a Retry-After that is not a number is ignored");
  check(authorization_retry_delay_ms(403, 0, "30") == 5000u, "a Retry-After in a 403 is ignored");
  check(!authorization_retry_delay_ms(400, 0), "a 400 is final");
  check(!authorization_retry_delay_ms(401, 0), "a 401 is left to the SIP stack's challenge handling");
  check(!authorization_retry_delay_ms(404, 0), "a 404 is final");
  check(!authorization_retry_delay_ms(412, 0), "a 412 is handled as RFC 3903 section 5 says");
  check(!authorization_retry_delay_ms(488, 0), "a 488 is final");
  check(!authorization_retry_delay_ms(603, 0), "a 603 is final");
  check(authorization_retry_delay_ms(477, 0) == 5000u, "a 477 (the SIP core could not send to the server) is retried");
  check(authorization_retry_delay_ms(478, 2) == 20000u, "a 478 (the server's address did not resolve) is retried");
  using mcx::client_rules::transient_authorization_refusal;
  check(transient_authorization_refusal(477) && transient_authorization_refusal(503),
        "a refresh refused with 477 or 503 is published anew");
  check(!transient_authorization_refusal(412) && !transient_authorization_refusal(404),
        "a 412 and a 404 are not transient refusals");
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.3: the outcome of an alias of the last status change from its status in a NOTIFY.
void test_alias_outcome() {
  using mcx::client_rules::alias_outcome;
  check(alias_outcome("activated", false) == "activated", "an activated alias is activated");
  check(alias_outcome("take-over-possible", false) == "take-over-possible", "an offered alias may be taken over");
  check(alias_outcome("", false) == "refused", "an activation left out is refused");
  check(!alias_outcome("activating", false), "an activating alias is still under way");
  check(!alias_outcome("activating", true), "an activating alias is under way for a deactivation too");
  check(!alias_outcome("deactivating", true), "a deactivating alias is still under way for a deactivation");
  check(alias_outcome("deactivating", false) == "activated", "a deactivating alias is still active");
  check(alias_outcome("", true) == "deactivated", "a deactivation left out is done");
  check(alias_outcome("activated", true) == "activated", "a deactivation the server kept active");
}

int main() {
  test_notifiers();
  test_has_warning();
  test_refresh_refusal();
  test_oidc_scope();
  test_percent_decode();
  test_profile_groups();
  test_floor_and_audio();
  test_authorization_retry();
  test_alias_outcome();
  if (failures) std::fprintf(stderr, "%d check(s) failed\n", failures);
  return failures ? 1 : 0;
}
