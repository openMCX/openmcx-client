//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// IdMS sign-in and token renewal of the identity management client: user authentication, refresh tokens and the
// service authorisation renewed with a new access token.
//
// Specifications: TS 24.482 V19.1.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19),
// TS 24.282 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: read the kept refresh token and nonce (TS 33.180 annex B.5) from the configured file, if any.
void Client::load_refresh_state() {
  refresh_loaded_ = true;
  const auto path = cfg_.at("oauth").value("refresh_token_file", std::string{});
  if (path.empty()) return;
  std::ifstream in(path);
  if (!in) return;
  try {
    const auto state = Json::parse(in);
    refresh_token_ = state.value("refresh_token", std::string{});
    auth_nonce_ = state.value("nonce", std::string{});
  } catch (const std::exception &) {
    // An unreadable file holds no refresh token: the client signs in with the password, or not at all.
    refresh_token_.clear();
    auth_nonce_.clear();
  }
}

//------------------------------------------------------------------------------
// Helper: keep the refresh token and nonce of a token response (TS 33.180 annex B.4.2.5, annex B.5.3), in a file
// only the owner can read.
void Client::keep_refresh_state(const Json &tokens) {
  refresh_token_ = tokens.value("refresh_token", std::string{});
  auth_nonce_ = tokens.value("nonce", std::string{});
  const auto path = cfg_.at("oauth").value("refresh_token_file", std::string{});
  if (path.empty()) return;
  const auto tmp = path + ".tmp";
  {
    std::ofstream out(tmp, std::ios::trunc);
    if (!out) return;
    out << Json{{"refresh_token", refresh_token_}, {"nonce", auth_nonce_}}.dump() << '\n';
  }
  std::filesystem::permissions(tmp, std::filesystem::perms::owner_read | std::filesystem::perms::owner_write);
  std::filesystem::rename(tmp, path);
}

//------------------------------------------------------------------------------
// TS 24.482 clause 6.2.1 and TS 33.180 annex B.4 and annex B.5, on the worker thread: obtain tokens with the refresh
// token when there is one; else with the authorization code of a sign-in in the user's browser (oauth.authorization:
// the code, the PKCE code verifier and the nonce of its request, used once); the password otherwise, or when IdMS
// refuses the refresh token and a password is configured (a lab client). A device without a password whose refresh
// token is refused (suspended, Revoke devices) must sign in again: activation, or the browser. Any other failure of
// the refresh grant (IdMS unreachable, a server error) is transient: the refresh token is kept and renew_token()
// tries again. The outcome, an error included, is in the returned grant; the run loop takes it (take_token_grant).
Client::TokenGrant Client::grant_tokens(const HttpClient &idms, TokenGrant grant) {
  try {
    const bool has_password = !grant.oauth.value("password", std::string{}).empty();
    if (!grant.refresh_token.empty()) {
      try {
        auto tokens = oidc_refresh(idms, grant.oauth, grant.refresh_token, grant.nonce);
        // TS 33.180 table B.5.3-1: IdMS may keep the refresh token instead of issuing another.
        if (!tokens.contains("refresh_token")) tokens["refresh_token"] = grant.refresh_token;
        grant.tokens = std::move(tokens);
        grant.kind = TokenGrant::Kind::refresh;
        grant.refusals = 0;
        return grant;
      } catch (const oidc_refresh_refused &) {
        // client_rules::refresh_refusal_final: a first refusal while the access token is valid keeps the refresh
        // token; renew_token() tries again 30 s later.
        ++grant.refusals;
        if (!client_rules::refresh_refusal_final(grant.refusals, has_password, std::time(nullptr), grant.expires))
          throw;
        grant.refresh_cleared = true;
        grant.refusals = 0;
        if (!has_password) throw;
      }
    }
    if (grant.oauth.contains("authorization")) {
      const auto authorization = grant.oauth.at("authorization");
      grant.authorization_used = true;
      grant.tokens = oidc_exchange_code(idms, grant.oauth, authorization.at("code"), authorization.at("code_verifier"),
                                        authorization.at("nonce"));
      grant.kind = TokenGrant::Kind::browser;
      return grant;
    }
    if (!has_password) throw std::runtime_error("IdMS refused the refresh token, and no password is configured");
    grant.tokens = oidc_login(idms, grant.oauth);
    grant.kind = TokenGrant::Kind::password;
  } catch (...) {
    grant.error = std::current_exception();
  }
  return grant;
}

//------------------------------------------------------------------------------
// The run loop takes a token grant: the refresh state (kept in oauth.refresh_token_file), the counters, a used
// authorization code, and then the tokens themselves (take_tokens); a failed grant is rethrown.
void Client::take_token_grant(const TokenGrant &grant) {
  refresh_refusals_ = grant.refusals;
  if (grant.refresh_cleared) refresh_token_.clear();
  if (grant.authorization_used) cfg_["oauth"].erase("authorization");
  if (grant.error) std::rethrow_exception(grant.error);
  keep_refresh_state(grant.tokens);
  if (grant.kind == TokenGrant::Kind::refresh) ++token_refreshes_;
  if (grant.kind == TokenGrant::Kind::browser) ++browser_logins_;
  if (grant.kind == TokenGrant::Kind::password) {
    ++password_logins_;
    // oauth.forget_password (a dispatcher position): the password serves the operator's sign-in only; renewals then
    // rely on the refresh token alone, so that IdMS refusing it (suspension, Revoke devices) ends the session.
    if (cfg_["oauth"].value("forget_password", false) && !refresh_token_.empty()) cfg_["oauth"].erase("password");
  }
  take_tokens(grant.tokens);
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.1.3.2.1: the token grant runs on the worker thread (token_job_), so that the SIP processing
// goes on meanwhile; done runs on the run loop. One grant at a time: a request while one runs waits a second.
void Client::request_tokens(std::function<void(std::exception_ptr)> done) {
  if (token_job_.busy()) {
    schedule_after(1000, [this, done = std::move(done)]() mutable { request_tokens(std::move(done)); });
    return;
  }
  if (!refresh_loaded_) load_refresh_state();
  TokenGrant grant;
  grant.oauth = oauth_settings(cfg_);
  grant.refresh_token = refresh_token_;
  grant.nonce = auth_nonce_;
  grant.refusals = refresh_refusals_;
  grant.expires = expires_;
  token_job_.start([&idms = idms_http_, grant = std::move(grant)] { return grant_tokens(idms, grant); },
                   [this, done = std::move(done)](TokenGrant result) {
                     std::exception_ptr error;
                     try {
                       take_token_grant(result);
                     } catch (...) {
                       error = std::current_exception();
                     }
                     done(error);
                   });
}

//------------------------------------------------------------------------------
// TS 24.482 clause 6.2.1: user authentication: check the identities the claims name (TS 33.180 table B.2.1.3-1)
// against the configured MCPTT ID, and the MCData and MCVideo IDs where IdMS names them; the client ID and SIP URI
// too where IdMS names them (the OpenMCX IdMS, an extension), then take the access token. A refresh may give no ID
// token (TS 33.180 table B.5.3-1): the identity stays the one checked at sign-in.
void Client::take_tokens(const Json &tokens) {
  if (tokens.contains("claims")) {
    const auto &claims = tokens.at("claims");
    const auto differs = [&claims](const char *claim, const std::string &configured) {
      return claims.contains(claim) && claims.at(claim) != configured;
    };
    if (claims.at("mcptt_id") != user() || differs("client_id", client_id()) ||
        differs("sip_uri", cfg_.at("sip").at("identity")) ||
        (mcdata_enabled() && differs("mcdata_id", mcdata_user())) ||
        (mcvideo_enabled() && differs("mcvideo_id", mcvideo_user())))
      throw std::runtime_error("OIDC identity does not match configured MCPTT/SIP client");
  } else if (token_.empty()) {
    throw std::runtime_error("IdMS gave no ID token at sign-in");
  }
  token_ = tokens.at("access_token");
  // TS 33.180 annex B.12: the client takes the access token's lifetime from expires_in.
  if (tokens.contains("expires_in") && tokens.at("expires_in").is_number_integer())
    expires_ = std::time(nullptr) + tokens.at("expires_in").get<std::int64_t>();
  else if (tokens.contains("claims"))
    expires_ = tokens.at("claims").at("exp");
  else
    throw std::runtime_error("IdMS gave no expires_in");
}

//------------------------------------------------------------------------------
// Helper: renew the access token (TS 33.180 annex B.5) before it expires: 10 minutes ahead, or halfway for a
// token that lives less than 20 minutes.
void Client::schedule_token_renewal() {
  const auto left = std::max<std::int64_t>(expires_ - std::time(nullptr), 0);
  const auto lead = left < 1200 ? left / 2 : left - 600;
  schedule_after(static_cast<std::uint64_t>(lead) * 1000, [this] { renew_token(); });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.2 (TS 24.282 clause 7.2.2 for MCData): service authorisation carries the
// access token, and the server holds it only until the token expires. A token
// renewed from the IdMS (on the worker thread, request_tokens) goes to the servers in a new PUBLISH with
// SIP-If-Match, which keeps the authorisation, the affiliations and any call. If renewal fails, the expiry in tick()
// still ends the authorisation.
void Client::renew_token() {
  request_tokens([this](std::exception_ptr error) {
    if (error) {
      failure("token renewal: " + error_text(error));
      if (std::time(nullptr) + 30 < expires_) schedule_after(30000, [this] { renew_token(); });
      return;
    }
    token_renewals_++;
    event("token_renewed", {{"expires_at", expires_}});
    if (authorized_) reauthorize();
    if (mcdata_enabled() && mcdata_authorized_) mcdata_authorize();
    // TS 24.281 clause 7.2.2: the MCVideo service authorisation carries the access token as well.
    if (mcvideo_enabled() && mcvideo_authorized_) mcvideo_authorize();
    schedule_token_renewal();
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.2: the service authorisation PUBLISH again, with the renewed token.
void Client::reauthorize() {
  if (authorise_by_register()) {
    register_authorization([this](const SipResponse &response) {
      if (response.status_code < 200) return;
      if (response.status_code != 200) {
        authorization_lost("service authorization renewal", response);
        return;
      }
      event("service_authorization_renewed");
    });
    return;
  }
  auto [type, body] = publish_body(false);
  std::vector<SipHeader> headers{
      {"P-Preferred-Service", mcptt_icsi}, {"Event", "poc-settings"}, {"Expires", "4294967295"}};
  if (!auth_etag_.empty()) headers.push_back({"SIP-If-Match", auth_etag_});
  sip_.publish(service(), type, body, headers, [this](const SipResponse &response) {
    if (response.status_code < 200) return;
    if (response.status_code != 200) {
      authorization_lost("service authorization renewal", response);
      return;
    }
    auth_etag_ = get_header(response.headers, "SIP-ETag");
    event("service_authorization_renewed");
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.2: upon a SIP 4xx, 5xx or 6xx response to the service authorisation PUBLISH, the client
// considers itself not authorised; with it go its affiliations (clause 9.2.1.2) and any call. A 412 (Conditional
// Request Failed) means the server holds no publication for the entity-tag, after a server restart for example:
// RFC 3903 section 5, the client "MUST NOT reattempt the PUBLISH request. Instead, to publish event state, the EPA
// SHOULD perform an initial publication, i.e., a PUBLISH request without a SIP-If-Match header field", and discards
// the entity-tag. The initial publication authorises the client again, and its affiliation status subscription and
// affiliation follow as after the first one (clauses 9.2.1.3 and 9.2.1.2). A refusal that can be transient, a refresh
// answered by the SIP core while the server restarts for example, likewise discards the entity-tag, and the initial
// publication follows after a growing delay (retry_authorization); any other refusal is final.
void Client::authorization_lost(const std::string &what, const SipResponse &response) {
  const int status = response.status_code;
  const bool was_authorized = authorized_;
  authorized_ = affiliated_ = false;
  if (was_authorized) {
    release();
    if (!dialog_.empty()) sip_.bye(dialog_);
  }
  failure(what + ": " + std::to_string(status));
  if (status != 412 && !client_rules::transient_authorization_refusal(status)) return;
  auth_etag_.clear();
  affiliation_etag_.clear();
  subscription_.clear();
  subscription_expires_ = 0;
  if (status == 412) {
    event("service_authorization_republished", {{"status", status}});
    schedule([this] { authorize(); });
  } else {
    retry_authorization(authorization_retry_, "mcptt", response, [this] {
      if (!authorized_) authorize();
    });
  }
}

} // namespace mcx
