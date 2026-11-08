//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData client: Interworking Security Data (TS 29.582 clauses 17.3.1 and 17.3.2), the opaque LMR security payload an
// LMR-aware client exchanges with an interworking function (TS 23.283 clause 10.12.1.1, the Non-3GPP security
// message; TS 100 392-19-1 clause 13.4.3 for TETRA key management).
//
// Specifications: TS 29.582 V19.0.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

namespace {

//------------------------------------------------------------------------------
// Helper: octets as lower-case hexadecimal, and back (throws on an odd or non-hexadecimal text).
std::string hex_of(std::string_view octets) {
  static constexpr char digits[] = "0123456789abcdef";
  std::string out;
  for (const auto octet : octets) {
    const auto value = static_cast<unsigned char>(octet);
    out += {digits[value >> 4], digits[value & 0xf]};
  }
  return out;
}

std::string octets_of(const std::string &hex) {
  if (hex.size() % 2 != 0 || hex.find_first_not_of("0123456789abcdefABCDEF") != std::string::npos)
    throw std::runtime_error("mcdata-intersd: the payload is hexadecimal octets");
  std::string out;
  for (std::size_t index = 0; index < hex.size(); index += 2)
    out += static_cast<char>(std::stoul(hex.substr(index, 2), nullptr, 16));
  return out;
}

} // namespace

//------------------------------------------------------------------------------
// TS 29.582 clause 17.3.1: mcdata-intersd <MCData ID> <hex payload> [network=tetra|p25|none]. The MESSAGE goes to the
// participating MCData function (step 5) with the MCData feature tag and ICSI (steps 2 to 4), the resource-lists
// naming the target (step 6) and the interworking-data body (step 7); its answer is the last result. Step 8: no
// security context with the IWF is set up here.
void Client::mcdata_security_data_command(std::istringstream &words) {
  std::string target, hex, word;
  std::optional<mcdata::ExternalNetwork> network = mcdata::ExternalNetwork::Tetra;
  words >> target >> hex;
  while (words >> word) {
    if (word == "network=tetra")
      network = mcdata::ExternalNetwork::Tetra;
    else if (word == "network=p25")
      network = mcdata::ExternalNetwork::P25;
    else if (word == "network=none")
      network.reset();
    else
      target.clear();
  }
  if (target.empty() || hex.empty())
    throw std::runtime_error("usage: mcdata-intersd <MCData ID> <hex payload> [network=tetra|p25|none]");
  mcdata::InterworkingSecurityData message{network, octets_of(hex)};
  std::vector<BodyPart> parts{{resource_lists_mime, private_call_resource_list(target)},
                              {mcdata::interworking_data_mime, mcdata::encode(message)}};
  mcdata_request(
      "", parts,
      {{"Accept-Contact", "*;+g.3gpp.mcdata;require;explicit"},
       {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcdata::icsi) + "\";require;explicit"}},
      [this, target](const SipResponse &response) {
        mcdata_security_data_last_result_ = {{"target", target},
                                             {"status", response.status_code},
                                             {"warning", std::string(get_header(response.headers, "Warning"))}};
        event("mcdata_security_data_answered", mcdata_security_data_last_result_);
      },
      "MESSAGE");
  event("mcdata_security_data_sent", {{"target", target}, {"bytes", message.payload.size()}});
}

//------------------------------------------------------------------------------
// TS 29.582 clause 17.3.2: an Interworking Security Data message for this client; its opaque payload kept with the
// sender and the external network ("mcdata_security_data_received"), 200. Step 1 (no resources: 480) applies when
// the list is full; a request for another user, or a body that does not decode, is refused.
SipResponse Client::mcdata_security_data_received(const std::vector<BodyPart> &parts) {
  SipResponse response;
  response.status_code = 200;
  const BodyPart *info_part = nullptr, *data_part = nullptr;
  for (const auto &part : parts) {
    if (part.content_type == mcdata::info_mime) info_part = &part;
    if (part.content_type == mcdata::interworking_data_mime) data_part = &part;
  }
  mcdata::Info info;
  mcdata::InterworkingSecurityData message;
  try {
    if (!info_part || !data_part) throw std::runtime_error("missing body");
    info = mcdata::parse_info(info_part->body);
    message = mcdata::decode_interworking_security_data(data_part->body);
  } catch (const std::exception &) {
    response.status_code = 400;
    return response;
  }
  if (info.request_uri != mcdata_user()) {
    response.status_code = 403;
    return response;
  }
  constexpr std::size_t kept = 64;
  if (mcdata_security_data_received_.size() >= kept) {
    response.status_code = 480; // steps 1 and 2
    return response;
  }
  const Json network = message.network ? Json(*message.network == mcdata::ExternalNetwork::Tetra ? "tetra" : "p25")
                                       : Json(nullptr);
  const Json entry = {{"from", info.calling_user}, {"network", network}, {"payload", hex_of(message.payload)}};
  mcdata_security_data_received_.push_back(entry);
  event("mcdata_security_data_received", entry);
  return response;
}

} // namespace mcx
