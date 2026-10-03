package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// position is a dispatcher position bound to MCX Admin (M3). Accounts are activated on it
// from MCX Admin activation links: each keeps its activated configuration, device key and
// certificate under <data>/accounts/<id>/, with no password. An operator signs in with an
// activated account's login and password; the engine runs only while someone is signed in,
// with the password in a runtime configuration on the tmpfs, and IdMS checks it. The
// session ends at sign-out, or when IdMS or the SIP core refuses the account (suspension,
// Revoke devices), which the engine reports.
type position struct {
	engine  *engine
	device  string // the position's device settings (no identity), read-only
	data    string // persistent: accounts
	runtime string // tmpfs: the running engine's configuration, activation passwords

	idle time.Duration // a session no page has used for this long ends (a browser closed without signing out)

	mu       sync.Mutex // guards the fields below; busy serialises sign-in and activation
	busy     sync.Mutex
	cookie   string
	operator string
	reason   string
	ending   bool
	seen     time.Time // the session's page was last heard from (its event stream asks every 250 ms)
}

const sessionCookie = "dispatcher_session"

func newPosition(e *engine, device, data string, idle time.Duration) (*position, error) {
	p := &position{engine: e, device: device, data: data, runtime: filepath.Dir(e.fifo), idle: idle}
	if err := os.MkdirAll(filepath.Join(data, "accounts"), 0o700); err != nil {
		return nil, err
	}
	e.onLine = p.watch
	go p.watchIdle()
	return p, nil
}

// watchIdle ends a session whose page has gone: nobody could answer its calls.
func (p *position) watchIdle() {
	for range time.Tick(time.Second) {
		p.mu.Lock()
		gone := p.cookie != "" && !p.ending && time.Since(p.seen) > p.idle
		p.mu.Unlock()
		if gone {
			p.end(fmt.Sprintf("No operator page was open for %s: signed out.", p.idle))
		}
	}
}

// accounts maps each login activated on this position to its directory.
func (p *position) accounts() map[string]string {
	out := map[string]string{}
	if raw, err := os.ReadFile(filepath.Join(p.data, "accounts.json")); err == nil {
		_ = json.Unmarshal(raw, &out)
	}
	return out
}

func (p *position) saveAccounts(m map[string]string) error {
	raw, _ := json.MarshalIndent(m, "", "  ")
	return writeSecret(filepath.Join(p.data, "accounts.json"), raw)
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// signedIn reports whether the request carries the current session's cookie.
func (p *position) signedIn(r *http.Request) bool {
	c, err := r.Cookie(sessionCookie)
	p.mu.Lock()
	defer p.mu.Unlock()
	ok := err == nil && p.cookie != "" && subtle.ConstantTimeCompare([]byte(c.Value), []byte(p.cookie)) == 1
	if ok {
		p.seen = time.Now()
	}
	return ok
}

// info is what the sign-in page shows: whether this browser is signed in, as whom, why
// the last session ended, and which logins are activated here.
func (p *position) info(r *http.Request) map[string]any {
	logins := []string{}
	for login := range p.accounts() {
		logins = append(logins, login)
	}
	in := p.signedIn(r)
	p.mu.Lock()
	defer p.mu.Unlock()
	out := map[string]any{"mode": "position", "signed_in": in, "busy": p.cookie != "" && !in, "reason": p.reason, "accounts": logins}
	if in {
		out["operator"] = p.operator
	}
	return out
}

func (p *position) begin(w http.ResponseWriter, operator string) {
	cookie := randomHex(32)
	p.mu.Lock()
	p.cookie, p.operator, p.reason, p.ending, p.seen = cookie, operator, "", false, time.Now()
	p.mu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: cookie, Path: "/", HttpOnly: true,
		SameSite: http.SameSiteStrictMode})
}

// end stops the engine and closes the session, with the reason the page shows.
func (p *position) end(reason string) {
	p.mu.Lock()
	if p.ending {
		p.mu.Unlock()
		return
	}
	p.ending = true
	operator := p.operator
	p.mu.Unlock()
	p.rememberGroup(operator)
	p.engine.stop()
	if dir, ok := p.accounts()[operator]; ok {
		_ = os.Remove(filepath.Join(dir, "idms-session.json"))
	}
	_ = os.Remove(filepath.Join(p.runtime, "engine.json"))
	p.mu.Lock()
	if p.cookie != "" || reason != "" {
		log.Printf("session of %q ended: %s", p.operator, reason)
	}
	p.cookie, p.operator, p.reason, p.ending = "", "", reason, false
	p.mu.Unlock()
}

// rememberGroup keeps the group the operator last worked in, for their next sign-in here.
func (p *position) rememberGroup(login string) {
	dir, ok := p.accounts()[login]
	if login == "" || !ok {
		return
	}
	if s, err := p.engine.state(); err == nil {
		if group, _ := s["group_id"].(string); group != "" {
			_ = writeSecret(filepath.Join(dir, "last-group"), []byte(group))
		}
	}
}

// Engine errors that mean the account may no longer be used: IdMS refused its token
// renewal, the refresh token (suspended, password changed, Revoke devices), or the SIP core refused its registration
// (credential suspended or removed by Revoke devices).
var (
	refusedAtIdMS = regexp.MustCompile(`^token renewal: .*(authentication failed|refused the refresh token)`)
	refusedAtCore = regexp.MustCompile(`^(SIP registration|service authorization renewal): (401|403|404)$`)
)

// watch reads the engine's output while a session runs.
func (p *position) watch(line string) {
	var ev struct{ Event, Reason string }
	if json.Unmarshal([]byte(line), &ev) != nil || ev.Event != "client_error" {
		return
	}
	switch {
	case refusedAtIdMS.MatchString(ev.Reason):
		go p.end("IdMS no longer accepts this session (account suspended, password changed or devices revoked): signed out.")
	case refusedAtCore.MatchString(ev.Reason):
		go p.end("The SIP core refused this position's registration (account suspended or devices revoked): signed out.")
	}
}

// waitReady waits for the engine to be authorised, or to end, and says which.
func (p *position) waitReady(timeout time.Duration) error {
	_, done := p.engine.running()
	deadline := time.After(timeout)
	tick := time.NewTicker(250 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-done:
			if msg := p.engine.lastError(); msg != "" {
				return errors.New(msg)
			}
			return errors.New("the engine stopped")
		case <-deadline:
			return errors.New("no answer from the servers in time")
		case <-tick.C:
			if s, err := p.engine.state(); err == nil && s["authorized"] == true {
				return nil
			}
		}
	}
}

// keepAlive ends the session when the engine stops on its own.
func (p *position) keepAlive() {
	_, done := p.engine.running()
	<-done
	p.mu.Lock()
	active, ending := p.cookie != "", p.ending
	p.mu.Unlock()
	if active && !ending {
		msg := p.engine.lastError()
		if msg == "" {
			msg = "stopped"
		}
		p.end("The engine ended (" + msg + "): signed out.")
	}
}

// signIn starts the engine for an activated account with the operator's password.
func (p *position) signIn(w http.ResponseWriter, login, password string) (int, error) {
	if !p.busy.TryLock() {
		return http.StatusConflict, errors.New("a sign-in or activation is in progress")
	}
	defer p.busy.Unlock()
	if running, _ := p.engine.running(); running {
		return http.StatusConflict, errors.New("an operator is signed in on this position; they must sign out first")
	}
	dir, ok := p.accounts()[login]
	if !ok {
		return http.StatusNotFound, errors.New("this account is not activated on this position: activate it with its link")
	}
	cfg, err := readJSON(filepath.Join(dir, "config.json"))
	if err != nil {
		return http.StatusInternalServerError, err
	}
	oauth, _ := cfg["oauth"].(map[string]any)
	if oauth == nil {
		return http.StatusInternalServerError, errors.New("the account's configuration has no IdMS settings")
	}
	// The operator's password signs in once: never a refresh token kept from an earlier
	// session, and the engine forgets the password once IdMS has given it a refresh token.
	delete(oauth, "refresh_token_file")
	oauth["password"] = password
	oauth["forget_password"] = true
	// The group the operator last worked in; the engine falls back to the user profile's
	// first group when the profile no longer lists it (mcptt.group_from_profile).
	if last, err := os.ReadFile(filepath.Join(dir, "last-group")); err == nil && word.Match(last) {
		if mcptt, ok := cfg["mcptt"].(map[string]any); ok {
			mcptt["group_id"] = string(last)
		}
	}
	raw, _ := json.MarshalIndent(cfg, "", "  ")
	runtime := filepath.Join(p.runtime, "engine.json")
	if err := writeSecret(runtime, raw); err != nil {
		return http.StatusInternalServerError, err
	}
	if err := p.engine.start(runtime); err != nil {
		_ = os.Remove(runtime)
		return http.StatusInternalServerError, err
	}
	if err := p.waitReady(60 * time.Second); err != nil {
		p.engine.stop()
		_ = os.Remove(runtime)
		time.Sleep(time.Second) // a little friction for password guessing
		if strings.Contains(err.Error(), "authentication failed") {
			return http.StatusUnauthorized, errors.New("IdMS refused the sign-in: wrong login or password, or the account is suspended")
		}
		return http.StatusBadGateway, fmt.Errorf("sign-in failed: %v", err)
	}
	// Signed in: the runtime configuration keeps the identities the page shows, not the password.
	delete(oauth, "password")
	raw, _ = json.MarshalIndent(cfg, "", "  ")
	_ = writeSecret(runtime, raw)
	p.begin(w, login)
	go p.keepAlive()
	return http.StatusOK, nil
}

// activate activates an account on this position from an MCX Admin activation link: a
// fresh copy of the device settings, a new device key; the engine keeps running signed in.
func (p *position) activate(w http.ResponseWriter, link, password string) (int, error) {
	if !p.busy.TryLock() {
		return http.StatusConflict, errors.New("a sign-in or activation is in progress")
	}
	defer p.busy.Unlock()
	if running, _ := p.engine.running(); running {
		return http.StatusConflict, errors.New("an operator is signed in on this position; they must sign out first")
	}
	if !strings.HasPrefix(link, "https://") || strings.ContainsAny(link, " \n\r\t") {
		return http.StatusBadRequest, errors.New("the activation link must be an https URL")
	}
	device, err := readJSON(p.device)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	dir := filepath.Join(p.data, "accounts", randomHex(8))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return http.StatusInternalServerError, err
	}
	raw, _ := json.MarshalIndent(device, "", "  ")
	config := filepath.Join(dir, "config.json")
	secret := filepath.Join(p.runtime, "activation-"+randomHex(8))
	if err := writeSecret(config, raw); err == nil {
		err = writeSecret(secret, []byte(password+"\n"))
	}
	if err != nil {
		_ = os.RemoveAll(dir)
		return http.StatusInternalServerError, err
	}
	defer os.Remove(secret)
	if err := p.engine.start(config, "--activate", link, "--password-file", secret); err != nil {
		_ = os.RemoveAll(dir)
		return http.StatusInternalServerError, err
	}
	if err := p.waitReady(90 * time.Second); err != nil {
		p.engine.stop()
		_ = os.RemoveAll(dir)
		return http.StatusBadRequest, fmt.Errorf("activation failed: %v", err)
	}
	// Keep the activated configuration without the password; the running engine has it.
	cfg, err := readJSON(config)
	if err != nil {
		p.engine.stop()
		_ = os.RemoveAll(dir)
		return http.StatusInternalServerError, err
	}
	oauth, _ := cfg["oauth"].(map[string]any)
	login, _ := oauth["username"].(string)
	delete(oauth, "password")
	// Activation leaves the engine a refresh token (idms-session.json) for this session only;
	// a later sign-in starts from the password (see signIn, and end, which removes the file).
	delete(oauth, "refresh_token_file")
	raw, _ = json.MarshalIndent(cfg, "", "  ")
	if err := writeSecret(config, raw); err != nil || login == "" {
		p.engine.stop()
		_ = os.RemoveAll(dir)
		return http.StatusInternalServerError, fmt.Errorf("cannot keep the activated account: %v", err)
	}
	accounts := p.accounts()
	if old, ok := accounts[login]; ok && old != dir {
		_ = os.RemoveAll(old) // activated again: the old key and certificate are no longer used
	}
	accounts[login] = dir
	if err := p.saveAccounts(accounts); err != nil {
		return http.StatusInternalServerError, err
	}
	p.begin(w, login)
	go p.keepAlive()
	return http.StatusOK, nil
}

// signOut ends this browser's session.
func (p *position) signOut(w http.ResponseWriter, r *http.Request) {
	if p.signedIn(r) {
		p.end("")
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true,
		SameSite: http.SameSiteStrictMode})
}
