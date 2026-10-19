//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// The sign-in on IdMS's own page (lab mode with oauth.login "browser"), for an IdMS whose
// login page the dispatcher cannot script, Keycloak for example:
// TS 33.180 annex B.4.2, the authorization code grant with PKCE. The engine builds the
// authorization request (mcx-client --authorization-request); this service sends the
// operator's browser to it and keeps its state, nonce and code verifier; IdMS sends the
// browser back to oauth.redirect_uri, this service's callback, with the code; the service
// checks the state and starts the engine with the code (oauth.authorization), which the
// engine exchanges and verifies. Renewals use the refresh token alone, so IdMS refusing it
// ends the session as in position mode. The configuration holds no password.

// callbackPath is where IdMS sends the browser back: oauth.redirect_uri names it.
const callbackPath = "/api/login/callback"

// loginCookie binds a sign-in under way to the browser that started it (RFC 6749 section
// 10.12): only that browser's callback is taken. SameSite Lax: IdMS's redirect back is a
// cross-site top-level navigation.
const loginCookie = "dispatcher_login"

// pendingLogin is an authorization request sent to the operator's browser.
type pendingLogin struct {
	state, nonce, verifier, binding string
	expires                         time.Time
}

// browserSignIn reports whether a lab configuration signs in on IdMS's page, and checks that
// its redirect URI is this service's callback.
func browserSignIn(config string) (bool, error) {
	if config == "" {
		return false, nil
	}
	cfg, err := readJSON(config)
	if err != nil {
		return false, err
	}
	oauth, _ := cfg["oauth"].(map[string]any)
	if login, _ := oauth["login"].(string); login != "browser" {
		return false, nil
	}
	redirect, _ := oauth["redirect_uri"].(string)
	if u, err := url.Parse(redirect); err != nil || u.Path != callbackPath {
		return false, fmt.Errorf("oauth.redirect_uri must be this dispatcher's %s (the address the browser opens), not %q", callbackPath, redirect)
	}
	return true, nil
}

func newBrowserPosition(e *engine, config string, idle time.Duration) *position {
	p := &position{engine: e, device: config, runtime: filepath.Dir(e.fifo), idle: idle, browser: true}
	if cfg, err := readJSON(config); err == nil {
		oauth, _ := cfg["oauth"].(map[string]any)
		issuer, _ := oauth["issuer"].(string)
		if u, err := url.Parse(issuer); err == nil {
			p.idms = u.Host
		}
	}
	e.onLine = p.watch
	go p.watchIdle()
	return p
}

// fail keeps the reason the sign-in page shows.
func (p *position) fail(reason string) {
	p.mu.Lock()
	p.reason = reason
	p.mu.Unlock()
	log.Printf("sign-in: %s", reason)
}

// startBrowserLogin builds the authorization request and returns IdMS's URL for the browser.
func (p *position) startBrowserLogin(w http.ResponseWriter) (string, error) {
	if !p.busy.TryLock() {
		return "", errors.New("a sign-in is in progress")
	}
	defer p.busy.Unlock()
	if running, _ := p.engine.running(); running {
		return "", errors.New("an operator is signed in on this position; they must sign out first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	args := append([]string{"--config", p.device, "--authorization-request"}, p.engine.args...)
	out, err := exec.CommandContext(ctx, p.engine.path, args...).Output()
	if err != nil {
		var exit *exec.ExitError
		if errors.As(err, &exit) && len(exit.Stderr) > 0 {
			err = errors.New(string(exit.Stderr))
		}
		return "", fmt.Errorf("IdMS cannot be reached: %v", err)
	}
	var request struct {
		URL          string `json:"url"`
		State        string `json:"state"`
		Nonce        string `json:"nonce"`
		CodeVerifier string `json:"code_verifier"`
	}
	if err := json.Unmarshal(out, &request); err != nil || request.URL == "" || request.State == "" || request.CodeVerifier == "" {
		return "", errors.New("the engine gave no authorization request")
	}
	binding := randomHex(32)
	p.mu.Lock()
	p.pending = &pendingLogin{state: request.State, nonce: request.Nonce, verifier: request.CodeVerifier,
		binding: binding, expires: time.Now().Add(5 * time.Minute)}
	p.reason = ""
	p.mu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: loginCookie, Value: binding, Path: callbackPath, MaxAge: 300,
		HttpOnly: true, SameSite: http.SameSiteLaxMode})
	return request.URL, nil
}

// finishBrowserLogin takes IdMS's redirect back: the state, then the engine with the code.
func (p *position) finishBrowserLogin(w http.ResponseWriter, r *http.Request) error {
	http.SetCookie(w, &http.Cookie{Name: loginCookie, Value: "", Path: callbackPath, MaxAge: -1, HttpOnly: true,
		SameSite: http.SameSiteLaxMode})
	if !p.busy.TryLock() {
		return errors.New("a sign-in is in progress")
	}
	defer p.busy.Unlock()
	query := r.URL.Query()
	p.mu.Lock()
	pending := p.pending
	p.pending = nil // one answer per request
	p.mu.Unlock()
	cookie, err := r.Cookie(loginCookie)
	if pending == nil || err != nil || time.Now().After(pending.expires) ||
		subtle.ConstantTimeCompare([]byte(cookie.Value), []byte(pending.binding)) != 1 ||
		subtle.ConstantTimeCompare([]byte(query.Get("state")), []byte(pending.state)) != 1 {
		return errors.New("this sign-in is not the one this browser started, or it took too long: sign in again")
	}
	// RFC 6749 section 4.1.2.1: IdMS refused (wrong password, the user cancelled).
	if e := query.Get("error"); e != "" {
		return fmt.Errorf("IdMS refused the sign-in (%s)", e)
	}
	if query.Get("code") == "" {
		return errors.New("IdMS gave no authorization code")
	}
	if running, _ := p.engine.running(); running {
		return errors.New("an operator is signed in on this position; they must sign out first")
	}
	cfg, err := readJSON(p.device)
	if err != nil {
		return err
	}
	oauth, _ := cfg["oauth"].(map[string]any)
	// The code signs in once; the engine keeps the refresh token in memory only.
	delete(oauth, "password")
	delete(oauth, "refresh_token_file")
	oauth["authorization"] = map[string]string{"code": query.Get("code"), "code_verifier": pending.verifier,
		"nonce": pending.nonce}
	raw, _ := json.MarshalIndent(cfg, "", "  ")
	runtime := filepath.Join(p.runtime, "engine.json")
	if err := writeSecret(runtime, raw); err != nil {
		return err
	}
	if err := p.engine.start(runtime); err != nil {
		_ = os.Remove(runtime)
		return err
	}
	if err := p.waitReady(60 * time.Second); err != nil {
		p.engine.stop()
		_ = os.Remove(runtime)
		return fmt.Errorf("sign-in failed: %v", err)
	}
	delete(oauth, "authorization")
	raw, _ = json.MarshalIndent(cfg, "", "  ")
	_ = writeSecret(runtime, raw)
	operator := ""
	if mcptt, ok := cfg["mcptt"].(map[string]any); ok {
		operator, _ = mcptt["user_id"].(string)
	}
	p.begin(w, operator)
	go p.keepAlive()
	return nil
}

// browserLogin sends the operator's browser to IdMS's page (the page's Sign in with IdMS).
func (a *api) browserLogin(w http.ResponseWriter, r *http.Request) {
	if a.pos == nil || !a.pos.browser {
		fail(w, http.StatusNotFound, errors.New("this dispatcher does not sign in on IdMS's page"))
		return
	}
	target, err := a.pos.startBrowserLogin(w)
	if err != nil {
		a.pos.fail(err.Error())
		http.Redirect(w, r, "/", http.StatusSeeOther)
		return
	}
	http.Redirect(w, r, target, http.StatusSeeOther)
}

// browserCallback is oauth.redirect_uri: IdMS's answer, then back to the page.
func (a *api) browserCallback(w http.ResponseWriter, r *http.Request) {
	if a.pos == nil || !a.pos.browser {
		fail(w, http.StatusNotFound, errors.New("this dispatcher does not sign in on IdMS's page"))
		return
	}
	if err := a.pos.finishBrowserLogin(w, r); err != nil {
		a.pos.fail(err.Error())
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}
