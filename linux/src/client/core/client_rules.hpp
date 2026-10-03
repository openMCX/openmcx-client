// Small rules of the MC client without client state, kept apart from the Client class so that
// tests/test_client_rules.cpp can check them: the notifiers of MCData disposition notifications, the warn-codes of a
// Warning header field, when a refused refresh token is dropped, the percent-decoding of an XCAP selector and the
// groups of an MCPTT user profile.
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// TS 24.484 V19.6.0 (Rel-19), RFC 3261, RFC 3986, RFC 6749.

#pragma once

#include <algorithm>
#include <cctype>
#include <cstdint>
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

} // namespace mcx::client_rules
