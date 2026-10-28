//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Small rules of the MC client without client state, kept apart from the Client class so that
// tests/test_client_rules.cpp can check them: the notifiers of MCData disposition notifications, the warn-codes of a
// Warning header field, when a refused refresh token is dropped, the scope of the authentication request, the percent-decoding of an XCAP selector, the
// escaped text of a control command, the groups of an MCPTT user profile, and the retry delay of a refused initial service authorisation.
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// TS 24.484 V19.6.0 (Rel-19), RFC 3261, RFC 3903, RFC 3986, RFC 6749.

#pragma once

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <iterator>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace mcx::client_rules {

//------------------------------------------------------------------------------
// The notifier of each disposition notification of one SIP MESSAGE, or nothing when the MESSAGE is to be refused.
// TS 24.282 clauses 15.1.5 and 15.1.6: the Sender MCData user ID IE is optional. A single notification names its
// notifier in the calling user (<mcdata-calling-user-id>) or the IE; an aggregated group notification (clause
// 12.2.3 step 15c) comes from a group (calling_group) and names every notifier in its own IE.
template <typename Notification>
std::optional<std::vector<std::string>> notifiers(const std::vector<Notification> &notices,
                                                  const std::string &calling_user, const std::string &calling_group) {
  const bool aggregated = notices.size() > 1;
  if (aggregated && calling_group.empty()) return std::nullopt;
  std::vector<std::string> senders;
  for (const auto &notice : notices) {
    if (!aggregated && !calling_user.empty())
      senders.push_back(calling_user);
    else if (notice.sender && !notice.sender->empty())
      senders.push_back(*notice.sender);
    else
      return std::nullopt;
  }
  return senders;
}

//------------------------------------------------------------------------------
// Whether a Warning header field value (RFC 3261 section 20.43, one or more comma-separated warning-values) carries
// the MC warning code: as the warn-code itself, or, as the MC services send it (TS 24.379 clause 4.4.1), as the
// first word of the quoted warn-text of a 399 warning-value.
inline bool has_warning(std::string_view header, unsigned code) {
  const auto wanted = std::to_string(code);
  const auto starts_with_code = [&](std::string_view text) {
    return text.starts_with(wanted) && (text.size() == wanted.size() || text[wanted.size()] == ' ');
  };
  std::size_t at = 0;
  while (at < header.size()) {
    while (at < header.size() && (header[at] == ' ' || header[at] == ',' || header[at] == '\t')) ++at;
    if (at >= header.size()) break;
    // One warning-value: warn-code SP warn-agent SP warn-text (a quoted string, with \" escapes).
    if (starts_with_code(header.substr(at))) return true;
    const auto quote = header.find('"', at);
    if (quote == std::string_view::npos) break;
    std::size_t end = quote + 1;
    while (end < header.size() && header[end] != '"') end += header[end] == '\\' ? 2 : 1;
    if (header.substr(at, 4) == "399 " && starts_with_code(header.substr(quote + 1, end - quote - 1))) return true;
    at = end + 1;
  }
  return false;
}

//------------------------------------------------------------------------------
// TS 33.180 annex B.5 with RFC 6749 section 5.2: whether the refresh token is dropped after IdMS refused it for the
// refusals-th time in a row. A refusal (invalid_grant) is final; but a library that reports every HTTP 400 or 401
// as a refusal may report a transient failure too, so a device without a password whose access token is still
// valid (now before expires) keeps the refresh token after a first refusal and lets the next attempt decide. A
// client with a password signs in with it at once.
inline bool refresh_refusal_final(unsigned refusals, bool has_password, std::int64_t now, std::int64_t expires) {
  return has_password || refusals >= 2 || now >= expires;
}

//------------------------------------------------------------------------------
// TS 33.180 table B.4.2.2-1 and clause B.4.2.2: the scope of the authentication request: openid, and for MCPTT and
// each further configured service (MCData, MCVideo) its service, configuration management and group management
// scopes, with the key management scope where the client is provisioned with keys (security configured).
inline std::string oidc_scope(bool data, bool video, bool keys, bool location = false) {
  std::string scope = "openid";
  for (const auto &[service, configured] :
       {std::pair<const char *, bool>{"ptt", true}, {"data", data}, {"video", video}}) {
    if (!configured) continue;
    for (const char *kind : {"_service", "_config_management_service", "_group_management_service"})
      scope += std::string(" 3gpp:mc:") + service + kind;
    if (keys) scope += std::string(" 3gpp:mc:") + service + "_key_management_service";
  }
  // TS 33.180 table B.4.2.2-1: the location management server's scope, for a client that
  // uses one (endpoints.lms).
  if (location) scope += " 3gpp:mc:location_management_service";
  return scope;
}

//------------------------------------------------------------------------------
// RFC 3986 section 2.1: percent-decode text; a '%' not followed by two hexadecimal digits is kept as it is.
inline std::string percent_decode(std::string_view text) {
  const auto hex = [](char digit) {
    return std::isdigit(static_cast<unsigned char>(digit)) ? digit - '0'
                                                           : std::tolower(static_cast<unsigned char>(digit)) - 'a' + 10;
  };
  std::string out;
  out.reserve(text.size());
  for (std::size_t index = 0; index < text.size(); ++index) {
    if (text[index] == '%' && index + 2 < text.size() && std::isxdigit(static_cast<unsigned char>(text[index + 1])) &&
        std::isxdigit(static_cast<unsigned char>(text[index + 2]))) {
      out += static_cast<char>(hex(text[index + 1]) * 16 + hex(text[index + 2]));
      index += 2;
    } else {
      out += text[index];
    }
  }
  return out;
}

//------------------------------------------------------------------------------
// The text of a control command with the "escaped" option (OpenMCX, not 3GPP): a command is one line, so a line break
// travels as "\n" and a backslash as "\\"; "\t" is a tab, and any other backslash is kept as it is.
inline std::string control_unescape(std::string_view text) {
  std::string out;
  out.reserve(text.size());
  for (std::size_t index = 0; index < text.size(); ++index) {
    const char next = index + 1 < text.size() ? text[index + 1] : '\0';
    if (text[index] == '\\' && (next == 'n' || next == 't' || next == '\\')) {
      out += next == 'n' ? '\n' : next == 't' ? '\t' : '\\';
      ++index;
    } else {
      out += text[index];
    }
  }
  return out;
}

//------------------------------------------------------------------------------
// Helper: replace the five predefined entities of XML 1.0 section 4.6 in character data; &amp; last, so that an
// escaped entity ("&amp;lt;") stays literal text.
inline std::string xml_unescape(std::string text) {
  const std::pair<std::string_view, std::string_view> entities[] = {
      {"&lt;", "<"}, {"&gt;", ">"}, {"&quot;", "\""}, {"&apos;", "'"}, {"&amp;", "&"}};
  for (const auto &[entity, character] : entities)
    for (auto at = text.find(entity); at != std::string::npos; at = text.find(entity, at + 1))
      text.replace(at, entity.size(), character);
  return text;
}

//------------------------------------------------------------------------------
// TS 24.484 clause 8.3.2: the groups of an MCPTT user profile, the <uri-entry> values of its <MCPTTGroupInfo>
// elements (<OnNetwork>/<MCPTTGroupInfo>/<entry>), in document order, without duplicates and at most 256. The
// elements may carry a namespace prefix; an empty <uri-entry/> is skipped.
inline std::vector<std::string> parse_profile_groups(std::string_view xml) {
  constexpr std::string_view group_info = "MCPTTGroupInfo", uri_entry = "uri-entry>";
  constexpr std::size_t max_groups = 256;
  std::vector<std::string> groups;
  for (auto open = xml.find(group_info); open != std::string_view::npos; open = xml.find(group_info, open + 1)) {
    if (open == 0 || (xml[open - 1] != '<' && xml[open - 1] != ':')) continue; // an opening tag, maybe prefixed
    const auto close = xml.find("MCPTTGroupInfo>", open + group_info.size());
    if (close == std::string_view::npos) break;
    const auto block = xml.substr(open, close - open);
    for (auto at = block.find(uri_entry); at != std::string_view::npos; at = block.find(uri_entry, at + 1)) {
      const auto value = at + uri_entry.size();
      const auto end = block.find('<', value);
      if (end == std::string_view::npos || block[at - 1] == '/') continue; // a closing tag
      auto uri = xml_unescape(std::string(block.substr(value, end - value)));
      if (!uri.empty() && std::find(groups.begin(), groups.end(), uri) == groups.end() && groups.size() < max_groups)
        groups.push_back(std::move(uri));
    }
    open = close;
  }
  return groups;
}

//------------------------------------------------------------------------------
// TS 24.380 table 8.2.2.1-1: the name of a floor control message by its type (the subtype's last four bits), for
// events and the status file.
inline std::string_view floor_message_name(std::uint8_t type) {
  switch (type) {
  case 0: return "Floor Request";
  case 1: return "Floor Granted";
  case 2: return "Floor Taken";
  case 3: return "Floor Deny";
  case 4: return "Floor Release";
  case 5: return "Floor Idle";
  case 6: return "Floor Revoke";
  case 8: return "Floor Queue Position Request";
  case 9: return "Floor Queue Position Info";
  case 10: return "Floor Ack";
  default: return "unknown";
  }
}

//------------------------------------------------------------------------------
// Helper: how many samples to drop from the front of a received-audio buffer of size samples so that at most
// limit remain; dropped in whole seconds (rate samples) past the limit, so the buffer is not shifted per packet.
inline std::size_t audio_overflow(std::size_t size, std::size_t limit, std::size_t rate) {
  return size > limit + rate ? size - limit : 0;
}

//------------------------------------------------------------------------------
// RFC 3550 appendix A.1: whether an RTP sequence number comes after the last one received, in modulo 2^16
// arithmetic (at most half the sequence space ahead). A copy received again, or an older packet, is not newer.
inline bool rtp_sequence_newer(std::uint16_t sequence, std::uint16_t last) {
  constexpr std::uint16_t half_space = 0x8000;
  const auto ahead = static_cast<std::uint16_t>(sequence - last);
  return ahead != 0 && ahead < half_space;
}

//------------------------------------------------------------------------------
// RFC 3550 appendix A.1 with the sliding window of RFC 3711 section 3.3.2: the sequence numbers of one SSRC kept from
// two paths (unicast and an MBMS subchannel, whose radio delays it by tens of milliseconds), which arrive out of order
// around a change of path. A packet is a copy when its sequence number was kept already, too old when it is the window
// or more behind the highest kept; anything else is fresh, an older one that is late included.
struct RtpReceiveWindow {
  static constexpr std::uint16_t size = 64;
  std::optional<std::uint32_t> ssrc;
  std::uint16_t highest = 0;
  std::uint64_t kept = 0; // bit n: highest - n was kept

  [[nodiscard]] bool fresh(std::uint32_t packet_ssrc, std::uint16_t sequence) const {
    if (ssrc != packet_ssrc || rtp_sequence_newer(sequence, highest)) return true;
    const auto behind = static_cast<std::uint16_t>(highest - sequence);
    return behind < size && !((kept >> behind) & 1U);
  }

  // How far a fresh sequence number is ahead of the highest kept (1: the next one), 0 when it is not ahead.
  [[nodiscard]] std::uint16_t ahead(std::uint32_t packet_ssrc, std::uint16_t sequence) const {
    if (ssrc != packet_ssrc || !rtp_sequence_newer(sequence, highest)) return 0;
    return static_cast<std::uint16_t>(sequence - highest);
  }

  void keep(std::uint32_t packet_ssrc, std::uint16_t sequence) {
    if (ssrc != packet_ssrc) {
      ssrc = packet_ssrc;
      highest = sequence;
      kept = 1;
    } else if (const auto step = ahead(packet_ssrc, sequence)) {
      kept = step >= size ? 1 : (kept << step) | 1U;
      highest = sequence;
    } else if (const auto behind = static_cast<std::uint16_t>(highest - sequence); behind < size) {
      kept |= std::uint64_t{1} << behind;
    }
  }
};

//------------------------------------------------------------------------------
// The delay before the client publishes its initial service authorisation again, or nothing when it does not.
// TS 24.379, TS 24.282 and TS 24.281 clause 7.2.2: a SIP 4xx, 5xx or 6xx response leaves the client not authorised,
// and they leave whether and when to try again to the client. A refusal that can be transient is retried: 403 (the
// server cannot authorise the user yet, for example while its user database reloads the profile after a restart),
// 408 (no response: the transaction timed out), 477 and 478 (the SIP core could not reach the server, for example
// while it restarts: sending to the next hop failed, its address did not resolve), 480, 486 (clause 7.3.3 steps 3a
// and 3b: the user is authorised on another client, which may log off or whose authorisation may end, for example a
// phone that died) and 500, and 503 (also a transport failure). The delay grows
// 5 s, 10 s, 20 s, then stays at 60 s; RFC 3261 section 20.33: a Retry-After in a 500 or 503 gives the delay instead
// (bounded to 1 s to 10 minutes). attempt counts the retries already made (0 before the first).
inline std::optional<std::uint64_t> authorization_retry_delay_ms(int status, unsigned attempt,
                                                                 std::string_view retry_after = {}) {
  switch (status) {
  case 403:
  case 408:
  case 477:
  case 478:
  case 480:
  case 486:
  case 500:
  case 503: break;
  default: return std::nullopt;
  }
  if (status == 500 || status == 503) {
    std::uint64_t seconds = 0;
    std::size_t index = 0;
    while (index < retry_after.size() && retry_after[index] == ' ') ++index;
    const std::size_t first = index;
    while (index < retry_after.size() && std::isdigit(static_cast<unsigned char>(retry_after[index])) &&
           index - first < 6)
      seconds = seconds * 10 + static_cast<std::uint64_t>(retry_after[index++] - '0');
    if (index > first) return std::clamp<std::uint64_t>(seconds, 1, 600) * 1000;
  }
  constexpr std::uint64_t delays[] = {5000, 10000, 20000};
  return attempt < std::size(delays) ? delays[attempt] : 60000;
}

//------------------------------------------------------------------------------
// Whether a refused service authorisation PUBLISH, initial or a refresh, is tried again (the refusal can be
// transient, authorization_retry_delay_ms).
inline bool transient_authorization_refusal(int status) { return authorization_retry_delay_ms(status, 0).has_value(); }

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.3 (TS 24.282 clause 22.2.1.3, TS 24.281 clause 20.2.1.3) with the statuses of clause
// 9A.3.1.2: the outcome of one alias of this client's last status change from its status in a NOTIFY (empty when the
// NOTIFY leaves it out), or std::nullopt while the change is still under way: "activating", or "deactivating" for a
// deactivation (a server not owning the alias reports these until the owning server's outcome, clause 9A.2.2.2.5).
inline std::optional<std::string> alias_outcome(std::string_view status, bool deactivation) {
  if (status == "activating" || (deactivation && status == "deactivating")) return std::nullopt;
  if (status == "activated" || status == "deactivating") return "activated";
  if (status == "take-over-possible") return "take-over-possible";
  return deactivation ? "deactivated" : "refused";
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.3.1.2 (TS 24.282 clause 8.4.1, TS 24.281 clause 8.3.1): whether a group listed in this client's
// affiliation NOTIFY counts as affiliated, as the participating function determines it (clause 9.2.2.2.11 step 3):
// "affiliating" or "affiliated", or no "status" (the attribute is optional), not "deaffiliating" ("deaffiliated"
// entries are left out, clause 9.2.2.2.5 step 3b).
inline bool counts_as_affiliated(std::string_view status) {
  return status != "deaffiliating" && status != "deaffiliated";
}

} // namespace mcx::client_rules
