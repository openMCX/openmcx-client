// MCData IP connectivity, MCData client side: session control and the GRE-in-UDP tunnel (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.582 V19.0.0 (Rel-19), RFC 8086 (GRE-in-UDP),
// RFC 791 (IPv4), RFC 792 (ICMP).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.282 clause 20.2.0a step 1: the configured IP address of this MCData client for the IP connectivity session.
std::string Client::ipcon_address() const {
  return cfg_.at("mcdata").value("ipconn", Json::object()).value("address", std::string{});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 20.2.1 steps 1 to 4: the media feature tags and ICSI of an IP connectivity SIP INVITE request.
std::vector<SipHeader> Client::ipcon_headers() const {
  const std::string icsi = mcdata::ipconn_icsi;
  return {{"Accept-Contact", "*;+g.3gpp.mcdata.ipconn;require;explicit"},
          {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(icsi) + "\";require;explicit"},
          {"P-Preferred-Service", icsi},
          {"Contact",
           "<" + sip_.contact_target() + ">;+g.3gpp.mcdata.ipconn;+g.3gpp.icsi-ref=\"" + url_encode(icsi) + "\""}};
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 20.2.0a and 20.2.0b: this client's end of the tunnel, sent from the port it
// listens on, so the source port of the fmtp (TS 24.582 clause 13.6) is the same port.
void Client::ipcon_bind() {
  const auto address = resolve_ipv4(cfg_.at("media").at("advertise"));
  ipcon_socket_ = std::make_unique<UdpSocket>(address, 0);
  const auto port = ipcon_socket_->port();
  ipcon_local_sdp_ = ipconn::tunnel_sdp({address, port, port}, 1);
}

//------------------------------------------------------------------------------
// Helper: forget the IP connectivity session (socket, dialog, SDP, peer and outstanding echoes).
void Client::ipcon_reset() {
  ipcon_socket_.reset();
  ipcon_dialog_.clear();
  ipcon_local_sdp_.clear();
  ipcon_peer_ = {};
  ipcon_outstanding_.clear();
}

//------------------------------------------------------------------------------
// TS 24.282 clause 20.2.1: an IP connectivity session with another MCData user or a functional alias; on the
// SIP 200 (OK) response the tunnel of TS 24.582 clause 13.1.2 is up.
void Client::ipcon_open(const std::string &target, bool to_alias, const std::string &called_alias) {
  if (!mcdata_authorized_) throw std::runtime_error("IP connectivity requires MCData service authorisation");
  require_profile({"allow-transmit-data"});
  if (ipcon_socket_) throw std::runtime_error("an IP connectivity session is already open");
  if (ipcon_address().empty())
    throw std::runtime_error("no IP connectivity address is configured (mcdata.ipconn.address)");
  mcdata::Info info;
  info.request_type = "one-to-one-ipconn";            // TS 24.282 clause 20.2.1 step 8a
  if (to_alias) info.call_to_functional_alias = true; // TS 24.282 clause 20.2.1 step 8b i
  info.called_functional_alias =
      called_alias; // TS 24.282 clause 20.2.1, after a SIP 300 (Multiple Choices) response, step 3
  if (!mcdata_alias_in_use_.empty() && mcdata_aliases_active_.contains(mcdata_alias_in_use_))
    info.functional_alias = mcdata_alias_in_use_; // TS 24.282 clause 20.2.1 step 8b ii
  ipcon_bind();
  ipcon_result_ = {{"target", target},
                   {"state", "inviting"},
                   {"status", nullptr},
                   {"originating", true},
                   {"address", ipcon_address()}};
  if (!called_alias.empty()) {
    ipcon_result_["called_alias"] = called_alias;
    ipcon_result_["selected"] = target; // the user the 300 (Multiple Choices) named
  }
  const std::vector<BodyPart> parts{
      {mcdata::info_mime, mcdata::info_xml(info)},
      {resource_lists_mime, private_call_resource_list(target)}}; // TS 24.282 clause 20.2.1 step 7
  ipcon_dialog_ = sip_.invite_sdp(
      mcdata_service(), ipcon_local_sdp_, mcdata_seal_signalling(parts),
      [this, target, to_alias](const SipResponse &raw) {
        const auto response = mcdata_opened_response(raw);
        if (response.status_code < 200) return;
        ipcon_result_["status"] = response.status_code;
        ipcon_result_["warning"] = std::string(get_header(response.headers, "Warning"));
        if (response.status_code == 300 && to_alias) {
          // TS 24.282 clause 20.2.1: invite the MCData ID selected for the alias, naming the alias.
          std::optional<mcdata::Info> answer;
          if (response.content_type.starts_with(mcdata::info_mime) && !response.body.empty())
            answer = mcdata::parse_info(response.body);
          for (const auto &part : response.parts)
            if (part.content_type == mcdata::info_mime) answer = mcdata::parse_info(part.body);
          ipcon_reset();
          if (!answer || answer->request_uri.empty()) {
            ipcon_result_["state"] = "rejected";
            failure("IP connectivity: 300 without a selected user");
            return;
          }
          schedule([this, selected = answer->request_uri, target] {
            try {
              ipcon_open(selected, false, target);
            } catch (const std::exception &error) {
              failure(error.what());
            }
          });
          return;
        }
        if (response.status_code >= 300) {
          ipcon_result_["state"] = "rejected";
          ipcon_reset();
          failure("IP connectivity: " + std::to_string(response.status_code));
          return;
        }
        try {
          for (const auto &part : response.parts)
            if (part.content_type.starts_with("application/sdp")) ipcon_peer_ = ipconn::parse_tunnel_sdp(part.body);
          if (!ipcon_peer_.port) throw std::runtime_error("the answer has no tunnel");
          ipcon_result_["state"] = "open"; // TS 24.582 clause 13.1.2: the tunnel is up
          ipcon_result_["peer"] = ipcon_peer_.address + ":" + std::to_string(ipcon_peer_.port);
          event("mcdata_ipconn_open", {{"target", ipcon_result_.value("target", "")}, {"peer", ipcon_result_["peer"]}});
        } catch (const std::exception &error) {
          // TS 24.282 clause 20.2.1: the media plane could not be established.
          ipcon_result_["state"] = "sdp-failed";
          failure(error.what());
          ipcon_close();
        }
      },
      ipcon_headers());
  event("mcdata_ipconn_requested", {{"target", target}, {"alias", to_alias}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 20.2.2: an incoming IP connectivity session is accepted when the client is free.
SipResponse Client::ipcon_incoming(const SipMessage &request, const mcdata::Info &info, const BodyPart &sdp) {
  SipResponse response;
  if (!ipcon_dialog_.empty() && request.dialog_id == ipcon_dialog_) {
    response.status_code = 200; // a session refresh keeps the tunnel
    response.local_sdp = ipcon_local_sdp_;
    return response;
  }
  if (ipcon_socket_) {
    response.status_code = 486;
    return response;
  }
  if (ipcon_address().empty()) {
    response.status_code = 480;
    return response;
  }
  try {
    ipcon_peer_ = ipconn::parse_tunnel_sdp(sdp.body);
  } catch (const std::exception &error) {
    event("mcdata_ipconn_sdp_rejected", {{"reason", error.what()}});
    response.status_code = 488;
    return response;
  }
  ipcon_bind();
  ipcon_dialog_ = request.dialog_id;
  // TS 24.282 clause 20.2.2 steps 3 to 3B: who is calling, and under which functional aliases.
  ipcon_result_ = {{"from", info.calling_user},
                   {"state", "open"},
                   {"originating", false},
                   {"address", ipcon_address()},
                   {"peer", ipcon_peer_.address + ":" + std::to_string(ipcon_peer_.port)},
                   {"referred_by", std::string(get_header(request.headers, "Referred-By"))}};
  if (!info.functional_alias.empty()) ipcon_result_["functional_alias"] = info.functional_alias;
  if (!info.called_functional_alias.empty()) ipcon_result_["called_alias"] = info.called_functional_alias;
  response.status_code = 200;
  response.local_sdp = ipcon_local_sdp_;
  event("mcdata_ipconn_incoming", {{"from", info.calling_user}, {"peer", ipcon_result_["peer"]}});
  return response;
}

//------------------------------------------------------------------------------
// Helper: release the IP connectivity session with a SIP BYE request and close the tunnel.
void Client::ipcon_close() {
  if (!ipcon_dialog_.empty()) {
    const auto dialog = ipcon_dialog_;
    schedule([this, dialog] { sip_.bye(dialog); });
  }
  if (ipcon_result_.is_object() && ipcon_result_.value("state", "") == "open") ipcon_result_["state"] = "closed";
  ipcon_reset();
}

//------------------------------------------------------------------------------
// TS 24.582 clauses 13.1.2, 13.1.3 and 13.4: encapsulate an IP packet in GRE-in-UDP and send it to the address and
// port of the other SDP.
void Client::ipcon_transmit(const std::vector<std::uint8_t> &packet) {
  ipcon_socket_->send(ipconn::encapsulate(packet), ipcon_peer_.address, ipcon_peer_.port);
  ++ipcon_sent_;
}

//------------------------------------------------------------------------------
// Helper: the IP application sends ICMP echo requests (RFC 792) into the tunnel.
void Client::ipcon_ping(const std::string &destination, unsigned count, std::size_t size) {
  if (!ipcon_socket_ || !ipcon_peer_.port) throw std::runtime_error("no IP connectivity session is open");
  if (!count || count > 100 || size > 1400)
    throw std::runtime_error("ipcon-ping sends 1 to 100 echoes of at most 1400 bytes");
  const auto identifier = static_cast<std::uint16_t>(random_ssrc());
  for (unsigned sequence = 1; sequence <= count; ++sequence) {
    std::vector<std::uint8_t> data(size);
    for (std::size_t index = 0; index < size; ++index)
      data[index] = static_cast<std::uint8_t>('a' + (index + sequence) % 26);
    const auto echo = ipconn::encode_icmp_echo({false, identifier, static_cast<std::uint16_t>(sequence), data});
    const auto packet =
        ipconn::encode_ipv4({ipcon_address(), destination, 1, 64, static_cast<std::uint16_t>(sequence), echo});
    ipcon_outstanding_[static_cast<std::uint32_t>(identifier) << 16 | sequence] = monotonic_ms();
    ipcon_transmit(packet);
  }
  event("mcdata_ipconn_ping", {{"destination", destination}, {"count", count}, {"identifier", identifier}});
}

//------------------------------------------------------------------------------
// TS 24.582 clauses 13.1.2, 13.1.3 and 13.4: datagrams from the other end are decapsulated and
// handed to the IP application (here: ICMP echo answered or measured); anything from elsewhere is dropped.
void Client::ipcon_tick() {
  if (!ipcon_socket_ || !ipcon_peer_.port) return;
  for (unsigned budget = 0; budget < 256; ++budget) {
    auto datagram = ipcon_socket_->receive();
    if (!datagram) return;
    if (!UdpSocket::matches(datagram->source, ipcon_peer_.address,
                            ipcon_peer_.source_port.value_or(ipcon_peer_.port))) {
      ++ipcon_dropped_;
      continue;
    }
    try {
      const auto opened = ipconn::decapsulate(datagram->bytes);
      if (opened.protocol != ipconn::ipv4) {
        ++ipcon_dropped_;
        continue;
      }
      const auto ip = ipconn::decode_ipv4(opened.packet);
      if (ip.destination != ipcon_address()) {
        ++ipcon_dropped_;
        continue;
      }
      ++ipcon_received_;
      if (ip.protocol != 1) continue;
      const auto echo = ipconn::decode_icmp_echo(ip.payload);
      if (!echo.reply) {
        ipcon_transmit(
            ipconn::encode_ipv4({ipcon_address(), ip.source, 1, 64, ip.identification,
                                 ipconn::encode_icmp_echo({true, echo.identifier, echo.sequence, echo.data})}));
        ++ipcon_echo_answered_;
        continue;
      }
      const auto key = static_cast<std::uint32_t>(echo.identifier) << 16 | echo.sequence;
      const auto sent = ipcon_outstanding_.find(key);
      if (sent == ipcon_outstanding_.end()) continue;
      bounded_push(ipcon_pings_, {{"from", ip.source},
                                  {"sequence", echo.sequence},
                                  {"bytes", echo.data.size()},
                                  {"ttl", ip.ttl},
                                  {"rtt_ms", monotonic_ms() - sent->second}});
      ipcon_outstanding_.erase(sent);
    } catch (const std::exception &error) {
      ++ipcon_dropped_;
      event("mcdata_ipconn_dropped", {{"reason", error.what()}});
    }
  }
}

} // namespace mcx
