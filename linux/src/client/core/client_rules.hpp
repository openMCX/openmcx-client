//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Small rules of the MC client without client state, kept apart from the Client class so that
// tests/test_client_rules.cpp can check them: the notifiers of MCData disposition notifications, the warn-codes of a
// Warning header field, when a refused refresh token is dropped, the scope of the authentication request, the percent-decoding of an XCAP selector, the
// escaped text of a control command, the groups of an MCPTT user profile, and the retry delay of a refused initial service authorisation, the
// implicit floor request word of a call command, the SDP answer parameters kept, the GMK of a call's group, the end of an MCData PCK context and
// the floor control streams of a call that go on under a new CSK, and the reg event timing and contact rules.
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19),
// TS 33.180 V19.4.0 (Rel-19), TS 24.484 V19.6.0 (Rel-19), TS 24.229 V19.7.0 (Rel-19), RFC 3261, RFC 3680, RFC 3903,
// RFC 3986, RFC 6749.

#pragma once

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <deque>
#include <stdexcept>
#include <iterator>
#include <optional>
#include <set>
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
// TS 33.180 annex B.5.1: a native MC client refreshes its access token with the refresh token; when, and how soon a
// failed renewal is tried again, is left to the client (oauth.token_renewal_lead_s, oauth.token_renewal_halfway_below_s,
// oauth.token_renewal_retry_ms). Defaults: default pending maintainer choice (no spec value).
inline constexpr std::int64_t default_token_renewal_lead_s = 600;
inline constexpr std::int64_t default_token_renewal_halfway_below_s = 1200;
inline constexpr std::uint64_t default_token_renewal_retry_ms = 30000;

//------------------------------------------------------------------------------
// The delay, seconds, until a token with left seconds to live is renewed: lead_s before it expires, or halfway for a
// token that lives less than halfway_below_s; never below 0.
constexpr std::int64_t token_renewal_delay_s(std::int64_t left, std::int64_t lead_s = default_token_renewal_lead_s,
                                             std::int64_t halfway_below_s = default_token_renewal_halfway_below_s) {
  return std::max<std::int64_t>(left < halfway_below_s ? left / 2 : left - lead_s, 0);
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
// TS 24.484 clause 8.3.2: the <uri-entry> values of the given list elements of an MCPTT user profile (as
// <OnNetwork>/<MCPTTGroupInfo>/<entry>, <RemoteGroupSelectionURIList>/<entry>), in document order, without duplicates
// and at most 256. The elements may carry a namespace prefix; an empty <uri-entry/> is skipped.
inline std::vector<std::string> parse_profile_uri_entries(std::string_view xml, std::string_view group_info) {
  constexpr std::string_view uri_entry = "uri-entry>";
  constexpr std::size_t max_groups = 256;
  std::vector<std::string> groups;
  for (auto open = xml.find(group_info); open != std::string_view::npos; open = xml.find(group_info, open + 1)) {
    if (open == 0 || (xml[open - 1] != '<' && xml[open - 1] != ':')) continue; // an opening tag, maybe prefixed
    const auto close = xml.find(std::string(group_info) + ">", open + group_info.size());
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

// The groups of the user profile: the <uri-entry> values of its <MCPTTGroupInfo> elements (TS 24.484 clause 8.3.2).
inline std::vector<std::string> parse_profile_groups(std::string_view xml) {
  return parse_profile_uri_entries(xml, "MCPTTGroupInfo");
}

//------------------------------------------------------------------------------
// The content of the first element of the given local name in an XML text (any namespace prefix), between its
// opening and closing tags; empty when there is none (or it is empty or unterminated).
inline std::string_view xml_element_content(std::string_view xml, std::string_view name) {
  for (std::size_t open = xml.find(name); open != std::string_view::npos; open = xml.find(name, open + 1)) {
    if (open == 0 || (xml[open - 1] != '<' && xml[open - 1] != ':')) continue;
    const auto after = open + name.size();
    if (after >= xml.size() || (xml[after] != '>' && xml[after] != ' ' && xml[after] != '\t' && xml[after] != '\n'))
      continue;
    // The opening tag starts at '<' (a prefix "p:" between); its end is the next '>'.
    const auto tag_end = xml.find('>', after);
    if (tag_end == std::string_view::npos || xml[tag_end - 1] == '/') return {};
    const auto lt = xml.rfind('<', open);
    const auto prefix = xml.substr(lt + 1, open - lt - 1); // "" or "p:"
    const auto close = xml.find("</" + std::string(prefix) + std::string(name) + ">", tag_end);
    if (close == std::string_view::npos) return {};
    return xml.substr(tag_end + 1, close - tag_end - 1);
  }
  return {};
}

// TS 24.484 clause 8.3.2: an <entry> of a user profile reached by the element path (e.g. {"MCPTT-group-call",
// "EmergencyCall", "MCPTTGroupInitiation"}): its "entry-info" attribute and the <uri-entry> values of the list.
// present: false when the path or its entry does not exist.
struct ProfileEntry {
  bool present = false;
  std::string entry_info;
  std::vector<std::string> uris;
};

inline ProfileEntry profile_entry(std::string_view xml, std::initializer_list<std::string_view> path) {
  ProfileEntry result;
  std::string_view block = xml;
  for (const auto element : path) {
    block = xml_element_content(block, element);
    if (block.empty()) return result;
  }
  for (auto at = block.find("entry"); at != std::string_view::npos; at = block.find("entry", at + 1)) {
    if (at == 0 || (block[at - 1] != '<' && block[at - 1] != ':') || at + 5 >= block.size() ||
        (block[at + 5] != ' ' && block[at + 5] != '>'))
      continue;
    const auto tag_end = block.find('>', at);
    if (tag_end == std::string_view::npos) break;
    const auto tag = block.substr(at, tag_end - at);
    result.present = true;
    if (const auto info = tag.find("entry-info=\""); info != std::string_view::npos) {
      const auto start = info + 12;
      result.entry_info = std::string(tag.substr(start, tag.find('"', start) - start));
    }
    // The <uri-entry> values of the list (opening tags only: preceded by '<' or a prefix's ':').
    constexpr std::string_view uri_entry = "uri-entry>";
    for (auto uri = block.find(uri_entry); uri != std::string_view::npos; uri = block.find(uri_entry, uri + 1)) {
      if (uri == 0 || (block[uri - 1] != '<' && block[uri - 1] != ':')) continue;
      if (block.rfind('<', uri) != std::string_view::npos && block[block.rfind('<', uri) + 1] == '/') continue;
      const auto value = uri + uri_entry.size();
      const auto end = block.find('<', value);
      if (end == std::string_view::npos) break;
      if (end > value) result.uris.push_back(xml_unescape(std::string(block.substr(value, end - value))));
    }
    break;
  }
  return result;
}

// TS 24.379 clauses 6.2.8.1.6, 6.2.8.1.8, 6.2.8.3.1.1 and 6.2.8.3.1.3: whether the profile's entry allows the target:
// entry_info `dedicated` ("DedicatedGroup", "UsePreConfigured") with the target among the <uri-entry> values, or
// `local` ("UseCurrentlySelectedGroup", "LocallyDetermined").
inline bool profile_entry_allows(const ProfileEntry &entry, std::string_view dedicated, std::string_view local,
                                 const std::string &target) {
  if (entry.entry_info == local) return true;
  return entry.entry_info == dedicated && std::find(entry.uris.begin(), entry.uris.end(), target) != entry.uris.end();
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
  case 7: return "Floor Revoke Request";
  case 8: return "Floor Queue Position Request";
  case 9: return "Floor Queue Position Info";
  case 10: return "Floor Ack";
  case 11: return "Unicast Media Flow Control";
  case 14: return "Queued Floor Requests";
  case 15: return "Floor Release Multi Talker";
  default: return "unknown";
  }
}

//------------------------------------------------------------------------------
// TS 24.380 table 8.2.3.15-2: bit G (Dual floor) of the Floor Indicator.
inline constexpr std::uint16_t floor_indicator_dual_floor = 0x0200;

//------------------------------------------------------------------------------
// Whether the Floor Release of 'U: has permission' carries the G-bit (Dual floor) in its Floor Indicator. On a Floor
// Revoke ([revoke_indicator] its Floor Indicator, 0 without one) TS 24.380 clause 6.2.4.5.4 steps 4a.i and 5a.i copy
// the Floor Revoke's G-bit; on the user's release clause 6.2.4.5.3 step 1c sets it "if the Floor Granted message
// included the G-bit" ([granted_dual]).
inline bool release_dual_floor(std::optional<std::uint16_t> revoke_indicator, bool granted_dual) {
  return revoke_indicator ? (*revoke_indicator & floor_indicator_dual_floor) != 0 : granted_dual;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.5.7: what a Floor Idle does in 'U: has permission' ([indicator] its Floor Indicator, 0
// without one). Item 1: with the indication that the participant is overriding without revoke stored, that indication
// is removed (EndOverriding); item 2: with the G-bit set and the indication that it is overridden without revoke
// stored, that one is removed (EndOverridden). In both the participant "shall remain in the 'U: has permission'
// state". Without either no procedure applies, and clause 6.2.4.1 has the message discarded and the state kept
// (Discard). No case gives up the permission to send media.
enum class HasPermissionIdle { Discard, EndOverriding, EndOverridden };
inline HasPermissionIdle has_permission_floor_idle(bool overriding, bool overridden, std::uint16_t indicator) {
  if (overriding) return HasPermissionIdle::EndOverriding;
  if ((indicator & floor_indicator_dual_floor) != 0 && overridden) return HasPermissionIdle::EndOverridden;
  return HasPermissionIdle::Discard;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.5.8 with TS 24.481 clause 7.2.4.2 (<audio-mixing-entity> "UE"): one mixing step over the
// frame queues of the talkers that send: when every queue has a frame, or one holds wait_frames or more (a talker
// paused), or flush, the first frame of each non-empty queue is taken and the frames are summed, saturated to 16 bits;
// nothing otherwise.
using FrameQueue = std::deque<std::vector<std::int16_t>>;
inline std::optional<std::vector<std::int16_t>> mix_step(const std::vector<FrameQueue *> &queues, bool flush,
                                                         std::size_t wait_frames = 5, std::size_t frame = 320) {
  std::size_t ready = 0, deepest = 0;
  for (const auto *queue : queues) {
    if (!queue->empty()) ++ready;
    deepest = std::max(deepest, queue->size());
  }
  if (!ready || (ready < queues.size() && deepest < wait_frames && !flush)) return std::nullopt;
  std::vector<std::int32_t> sum(frame, 0);
  for (auto *queue : queues) {
    if (queue->empty()) continue;
    const auto &pcm = queue->front();
    for (std::size_t i = 0; i < frame && i < pcm.size(); ++i) sum[i] += pcm[i];
    queue->pop_front();
  }
  std::vector<std::int16_t> mixed(frame);
  for (std::size_t i = 0; i < frame; ++i) mixed[i] = static_cast<std::int16_t>(std::clamp(sum[i], -32768, 32767));
  return mixed;
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

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.5 3) (TS 24.281 clause 8.2.1.5 3)): the change an accepted negotiated-mode request makes:
// a) the groups of the accepted affiliation list the client is not affiliated to, b) those of the accepted
// de-affiliation list it is affiliated to; each once, in the order of the lists. `affiliated` tells a group's status.
template <typename Affiliated>
std::pair<std::vector<std::string>, std::vector<std::string>>
affiliation_change(const std::vector<std::string> &affiliate, const std::vector<std::string> &leave,
                   Affiliated affiliated) {
  std::pair<std::vector<std::string>, std::vector<std::string>> out;
  auto &[added, removed] = out;
  for (const auto &group : affiliate)
    if (!affiliated(group) && std::find(added.begin(), added.end(), group) == added.end()) added.push_back(group);
  for (const auto &group : leave)
    if (affiliated(group) && std::find(removed.begin(), removed.end(), group) == removed.end())
      removed.push_back(group);
  return out;
}

//------------------------------------------------------------------------------
// TS 24.379 annex F.4 (TS 24.281 annex F.4): the text of each <group> inside the <affiliate> or <de-affiliate> element
// (`name`) of an affiliation command <command-list> (any namespace prefix ignored; an empty <group> is skipped).
inline std::vector<std::string> affiliation_command_groups(const std::string &xml, const std::string &name) {
  std::vector<std::string> out;
  const auto open = xml.find(":" + name + ">") != std::string::npos ? xml.find(":" + name + ">")
                                                                     : xml.find("<" + name + ">");
  if (open == std::string::npos) return out;
  const auto close = xml.find(name + ">", open + name.size() + 2);
  const auto section = xml.substr(open, close == std::string::npos ? std::string::npos : close - open);
  for (std::size_t at = 0; (at = section.find("group>", at)) != std::string::npos;) {
    const auto start = at + 6;
    const auto end = section.find('<', start);
    if (end == std::string::npos) break;
    if (section[at - 1] != '/') {
      std::string value = section.substr(start, end - start);
      if (!value.empty()) out.push_back(value);
    }
    at = end;
  }
  return out;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 14.2.5 (whether a call's INVITE is an implicit floor request is an implementation option): the
// last word of a call command (or of an upgrade to emergency or imminent peril, "emergency on [alert]", "imminent on"),
// "implicit", "implicit=<WAV>" or "no-implicit", taken off (command: the rest).
// requested: none when the command ends in no such word; rejoin: the word ends a "rejoin", which TS 24.380 clause
// 6.2.1 item 2 never lets be an implicit floor request.
struct ImplicitWord {
  std::string command;
  std::optional<bool> requested;
  std::string wav;
  bool rejoin = false;
};

inline ImplicitWord implicit_word(std::string_view cmd) {
  ImplicitWord result{std::string(cmd), std::nullopt, {}, false};
  const auto space = cmd.rfind(' ');
  if (space == std::string_view::npos) return result;
  const auto word = cmd.substr(space + 1);
  const auto head = cmd.substr(0, space);
  constexpr std::string_view wav_prefix = "implicit=";
  if (word != "implicit" && word != "no-implicit" && !(word.starts_with(wav_prefix) && word.size() > wav_prefix.size()))
    return result;
  if (head == "rejoin") {
    result.rejoin = true;
    return result;
  }
  const bool call_command = head == "call" || head.starts_with("call ") || head == "chat" ||
                            head.starts_with("chat ") || head.starts_with("private ") ||
                            head.starts_with("private-fa ") || head.starts_with("first-to-answer ") ||
                            head.starts_with("first-to-answer-fa ") || head.starts_with("adhoc ") ||
                            // TS 24.379 clause 6.4: the re-INVITE of an upgrade of the call to an emergency or
                            // imminent peril call (clauses 10.1.1.2.1.3, 10.1.2.2.1.3, 11.1.1.2.1.5 4)).
                            head == "emergency on" || head == "emergency on alert" || head == "imminent on";
  if (!call_command) return result;
  result.command = std::string(head);
  result.requested = word != "no-implicit";
  if (word.starts_with(wav_prefix)) result.wav = std::string(word.substr(wav_prefix.size()));
  return result;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 14.4: an fmtp parameter of the SDP answer the offer did not have is discarded ("the offerer
// shall discard those attribute parameters"); one it had is kept as the answer says.
constexpr bool answer_parameter_kept(bool in_answer, bool offered) { return in_answer && offered; }

//------------------------------------------------------------------------------
// TS 33.180 clauses 7.3.11 and 7.3.12: "The GMK and the GMK-ID from the identified preconfigured group shall be used"
// for a call on a regroup using a preconfigured group; any other group's call (clauses 7.3.4 and 7.3.5: a broadcast
// group's too) is keyed with that group's GMK. preconfigured_of: the preconfigured group of a regroup this client was
// told of, empty for any other group.
template <class Lookup> std::string key_group(const std::string &group, Lookup preconfigured_of) {
  const std::string preconfigured = preconfigured_of(group);
  return preconfigured.empty() ? group : preconfigured;
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.7 and annex E.6: among the keys for which usable(key) holds (the group among its MC group IDs,
// active, not revoked, not expired), the one of the latest Activation time (activation(key)); null if none.
template <class Map, class Usable, class Activation>
const typename Map::mapped_type *newest_usable(const Map &keys, Usable usable, Activation activation) {
  const typename Map::mapped_type *best = nullptr;
  for (const auto &entry : keys)
    if (usable(entry.second) && (!best || activation(entry.second) > activation(*best))) best = &entry.second;
  return best;
}

//------------------------------------------------------------------------------
// TS 33.180 annex E.6.10: "The 'Expiry time' element shall define the time in UTC at which the associated key shall no
// longer be used ... A value of 0 shall imply the key shall not expire." The end of an MCData PCK context (Unix
// seconds): the end of the local lifetime (local_end, from mcdata.pck_lifetime_s), or the PCK's own Expiry time when
// it carries one that is earlier (key_expiry 0: none, or a PCK without Key Parameters).
inline std::uint64_t data_pck_end(std::uint64_t local_end, std::uint64_t key_expiry) {
  return key_expiry && key_expiry < local_end ? key_expiry : local_end;
}

//------------------------------------------------------------------------------
// TS 24.380 clauses 14.2.7 and 14.3.8: "mc_floor_ssrc" is in an offer only when the offerer supports multiplexing,
// and in the answer only when the offer had it; 0 is none. With the server's (remote, of its offer or answer) the
// floor control messages are multiplexed: this client sends them with the server's SSRC, and takes those that carry
// its own (local, the one it allocated, clause 4.3.3.1). Without it there is no multiplexing: the SSRC field is
// "the SSRC of the floor participant sending" the message (clause 8.2.4 and the other messages of clause 8.2), this
// client's own, and the server's messages carry the server's, which no SDP tells: they are the session's by the
// address and port they come from.
inline std::uint32_t floor_ssrc_to_send(std::uint32_t local, std::uint32_t remote) { return remote ? remote : local; }
inline bool floor_ssrc_received(std::uint32_t ssrc, std::uint32_t local, std::uint32_t remote) {
  return !remote || ssrc == local;
}
// The "mc_floor_ssrc" of this client's answer to an offer with [offered] (TS 24.380 clauses 14.3.1, 14.3.8).
inline std::uint32_t answer_floor_ssrc(std::uint32_t local, std::uint32_t offered) { return offered ? local : 0; }

//------------------------------------------------------------------------------
// TS 24.380 clause 14.3: the media plane control fmtp parameters of this client's SDP answer, each by its own item.
// 14.3.2: "mc_queueing" when this client supports queueing of floor requests ([queueing_supported]), whatever the
// offer (the offer conditions only the controlling and non-controlling functions). 14.3.3: "mc_priority" returns the
// offer's value. 14.3.8: "mc_floor_ssrc" (this client's, [local_floor_ssrc]) only when the offer had one. 14.3.4 to
// 14.3.6 ("mc_granted", "mc_implicit_request", "mc_ssrc") are the server's: never in this client's answer. 14.3.7 and
// 14.2.6: with "mc_no_floor_ctrl" no floor control related parameter is written. An offer without the media plane
// control channel ([control_channel] false: a private call without floor control, TS 24.379 clauses 6.2.2 and 11.1.2)
// is answered without one, so with no fmtp parameter at all (TS 24.380 clause 14.3.1).
struct AnswerFloorParameters {
  std::uint32_t floor_ssrc = 0;
  bool queueing = false;
  std::optional<std::uint8_t> floor_priority;
};
inline AnswerFloorParameters answer_floor_parameters(bool control_channel, std::uint32_t local_floor_ssrc,
                                                     bool queueing_supported, bool no_floor_control,
                                                     std::uint32_t offered_floor_ssrc,
                                                     std::optional<std::uint8_t> offered_priority) {
  AnswerFloorParameters answer;
  if (!control_channel) return answer;
  answer.floor_ssrc = answer_floor_ssrc(local_floor_ssrc, offered_floor_ssrc);
  if (no_floor_control) return answer;
  answer.queueing = queueing_supported;
  answer.floor_priority = offered_priority;
  return answer;
}

//------------------------------------------------------------------------------
// RFC 3711 section 3.4 (TS 33.180 clauses 9.4.7 and 9.4.8): "after a re-key, the SRTCP index MUST NOT be reset to zero
// again". The floor (or transmission) control streams of the call under way that go on under a new CSK
// (ProtectedCall::set_floor), in both directions (TS 24.380 and TS 24.581 clause 4.3.3.1: each side allocates the
// SSRC it receives): this client's SSRC [local] and the server's [remote], once negotiated; 0 is none.
inline std::set<std::uint32_t> call_floor_ssrcs(std::uint32_t local, std::optional<std::uint32_t> remote) {
  std::set<std::uint32_t> ssrcs{local};
  if (remote) ssrcs.insert(*remote);
  ssrcs.erase(0);
  return ssrcs;
}

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.3: the reg event subscription is refreshed "either 600 seconds before the expiration time if
// the initial subscription was for greater than 1200 seconds, or when half of the time has expired if the initial
// subscription was for 1200 seconds or less"; clause 5.1.1.4.1 times a reregistration the same way from the duration
// of "the previous registration" (also when a shortened registration's expiration time is adjusted, clause 5.1.1.5A
// item 1). The delay from now, seconds: the expiration time is remaining seconds away, the subscription (or
// registration) was for duration seconds; 0 when the time has already come.
inline std::uint64_t refresh_delay_s(std::uint64_t duration, std::uint64_t remaining) {
  const std::uint64_t before = duration > 1200 ? 600 : duration / 2;
  return remaining > before ? remaining - before : 0;
}

//------------------------------------------------------------------------------
// The user, host and port of a SIP URI ("user@host:port", without scheme, parameters or angle brackets), for matching
// the <uri> of a reginfo <contact> with this client's own contact address (TS 24.229 clause 5.1.1.5A: "set to the
// Contact address that the UE registered"), whose parameters may be written differently.
inline std::string contact_address(std::string_view uri) {
  auto value = std::string(uri);
  if (const auto open = value.find('<'); open != std::string::npos) value = value.substr(open + 1);
  if (const auto colon = value.find(':'); colon != std::string::npos) value = value.substr(colon + 1);
  return value.substr(0, value.find_first_of(";>?"));
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 14.2.3 and 14.2.6: the "mc_priority" / "mc_reception_priority" value offered for a configured
// priority (TS 24.484 clause 9.3.2.1 item 8 e) v), 0 to 255): none for the default priority 0; the SDP syntax has at
// most two digits (clause 12.1.2.3), so a higher value asks for 99.
inline std::optional<std::uint8_t> offered_mc_priority(unsigned configured) {
  if (configured == 0) return std::nullopt;
  return static_cast<std::uint8_t>(std::min(configured, 99u));
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 14.3.3, 14.3.6, 14.4 and 12.1.2.2: the negotiated maximum priority of a media plane control
// channel parameter. As answerer the client returned the offered value (clause 14.3.3: "shall return the value
// included in the SDP offer"; theirs is that offer's). As offerer: a parameter of the answer that the offer did not
// have is discarded (clause 14.4), and the answered value is at most the offered one (clause 12.1.2.2); none means the
// default priority (clause 12.1.2.2 NOTEs 1 and 2).
inline std::optional<std::uint8_t> negotiated_mc_priority(bool answerer, std::optional<std::uint8_t> ours,
                                                          std::optional<std::uint8_t> theirs) {
  if (answerer) return theirs;
  if (!ours || !theirs) return std::nullopt;
  return std::min(*ours, *theirs);
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 6.2.4.3.2 step 2a and 6.2.5.3.3 step 1a: the Transmission Priority / Reception Priority value of a
// request, "if a different priority than the normal priority is required ... not higher than negotiated": the
// requested (configured) priority capped by the negotiated one; none (the field left out, the default priority of
// clauses 9.2.3.2 and 9.2.3.19) when the default is requested or nothing above it was negotiated.
inline std::optional<std::uint8_t> request_priority(unsigned requested, std::optional<std::uint8_t> negotiated) {
  if (requested == 0 || !negotiated || *negotiated == 0) return std::nullopt;
  return static_cast<std::uint8_t>(std::min<unsigned>(requested, *negotiated));
}

//------------------------------------------------------------------------------
// TS 24.282 clause 15.2.13: binary Payload data given as hexadecimal text ("0a1b..."), and the reverse.
inline std::string hex_bytes(std::string_view text) {
  if (text.size() % 2) throw std::invalid_argument("hex data has an odd number of digits");
  const auto digit = [](char c) {
    if (!std::isxdigit(static_cast<unsigned char>(c))) throw std::invalid_argument("not a hex digit");
    return std::isdigit(static_cast<unsigned char>(c)) ? c - '0' : std::tolower(static_cast<unsigned char>(c)) - 'a' + 10;
  };
  std::string out;
  for (std::size_t at = 0; at < text.size(); at += 2) out += static_cast<char>(digit(text[at]) * 16 + digit(text[at + 1]));
  return out;
}

inline std::string hex_text(std::string_view data) {
  static constexpr char digits[] = "0123456789abcdef";
  std::string out;
  for (const auto c : data) {
    out += digits[static_cast<unsigned char>(c) >> 4];
    out += digits[static_cast<unsigned char>(c) & 15];
  }
  return out;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 15.2.13: a LOCATION payload, 3 octets of latitude and 3 of longitude coded as in TS 23.032 clause
// 6.1: the sign bit (south) and N with N <= 2^23 |lat| / 90 < N + 1; the longitude in 2's complement, N <= 2^24 lon /
// 360 < N + 1. The text is "<latitude>,<longitude>" in degrees.
inline std::string location_payload(std::string_view text) {
  const auto comma = text.find(',');
  if (comma == std::string_view::npos) throw std::invalid_argument("location is <latitude>,<longitude>");
  const double lat = std::stod(std::string(text.substr(0, comma))), lon = std::stod(std::string(text.substr(comma + 1)));
  if (lat < -90 || lat > 90 || lon < -180 || lon >= 180) throw std::invalid_argument("location out of range");
  auto n = static_cast<std::uint32_t>(std::min(std::floor(std::fabs(lat) / 90.0 * 8388608.0), 8388607.0));
  if (lat < 0) n |= 0x800000u;
  const auto m = static_cast<std::uint32_t>(static_cast<std::int32_t>(std::floor(lon / 360.0 * 16777216.0))) & 0xFFFFFFu;
  return std::string{static_cast<char>(n >> 16), static_cast<char>((n >> 8) & 0xFF), static_cast<char>(n & 0xFF),
                     static_cast<char>(m >> 16), static_cast<char>((m >> 8) & 0xFF), static_cast<char>(m & 0xFF)};
}

} // namespace mcx::client_rules
