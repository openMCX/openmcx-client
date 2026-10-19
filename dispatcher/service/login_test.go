//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// A lab dispatcher that signs in on IdMS's page, with the fake engine (testdata/fake-engine).
func testBrowserPosition(t *testing.T) (*httptest.Server, *position, string) {
	t.Helper()
	dir := t.TempDir()
	run := filepath.Join(dir, "run")
	if err := os.MkdirAll(run, 0o700); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(dir, "config.json")
	settings := map[string]any{"control_fifo": filepath.Join(run, "control"), "status_file": filepath.Join(run, "status.json"),
		"media": map[string]any{"live": map[string]any{"port": 0, "send_port": 0}},
		"oauth": map[string]any{"login": "browser", "issuer": "https://idms.example.test",
			"redirect_uri": "http://127.0.0.1:8080" + callbackPath, "refresh_token_file": filepath.Join(dir, "refresh.json")},
		"mcptt": map[string]any{"user_id": "sip:d1.mcptt@example.org", "group_id": "sip:ops@example.org"}}
	raw, _ := json.Marshal(settings)
	if err := os.WriteFile(config, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	if browser, err := browserSignIn(config); !browser || err != nil {
		t.Fatalf("browser sign-in not recognised: %v %v", browser, err)
	}
	fake, _ := filepath.Abs("testdata/fake-engine")
	e, err := newEngine(fake, config, nil)
	if err != nil {
		t.Fatal(err)
	}
	pos := newBrowserPosition(e, config, time.Minute)
	mux := http.NewServeMux()
	(&api{engine: e, outbox: filepath.Join(dir, "outbox"), pos: pos}).routes(mux)
	server := httptest.NewServer(mux)
	t.Cleanup(func() { e.stop(); server.Close() })
	return server, pos, dir
}

// get sends a GET without following redirects, with the given cookies.
func get(t *testing.T, server *httptest.Server, path string, cookies ...*http.Cookie) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("GET", server.URL+path, nil)
	for _, c := range cookies {
		req.AddCookie(c)
	}
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	res, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	return res
}

func cookieNamed(res *http.Response, name string) *http.Cookie {
	for _, c := range res.Cookies() {
		if c.Name == name && c.MaxAge >= 0 {
			return c
		}
	}
	return nil
}

// start opens the sign-in: the browser goes to IdMS with a cookie binding it to the request.
func start(t *testing.T, server *httptest.Server) *http.Cookie {
	t.Helper()
	res := get(t, server, "/api/login/browser")
	binding := cookieNamed(res, loginCookie)
	if res.StatusCode != http.StatusSeeOther || res.Header.Get("Location") != "https://idms.example.test/authorize?state=s1" || binding == nil {
		t.Fatalf("sign-in start: %d %q %v", res.StatusCode, res.Header.Get("Location"), binding)
	}
	return binding
}

func reason(t *testing.T, server *httptest.Server) string {
	t.Helper()
	_, info, _ := call(t, server, "GET", "/api/session", "", "")
	r, _ := info["reason"].(string)
	return r
}

func TestBrowserSignIn(t *testing.T) {
	server, pos, dir := testBrowserPosition(t)
	if _, info, _ := call(t, server, "GET", "/api/session", "", ""); info["mode"] != "browser" || info["signed_in"] != false ||
		info["idms"] != "idms.example.test" {
		t.Fatalf("session before sign-in: %v", info)
	}
	if status, _, _ := call(t, server, "POST", "/api/login", "", `{"username":"d1","password":"right"}`); status != http.StatusNotFound {
		t.Fatalf("password sign-in on a browser dispatcher: %d", status)
	}

	// Another state, or a callback without the browser's binding, is refused.
	binding := start(t, server)
	if res := get(t, server, callbackPath+"?code=good&state=other", binding); res.StatusCode != http.StatusSeeOther ||
		!strings.Contains(reason(t, server), "not the one this browser started") {
		t.Fatalf("another state: %d %q", res.StatusCode, reason(t, server))
	}
	start(t, server)
	if get(t, server, callbackPath+"?code=good&state=s1"); !strings.Contains(reason(t, server), "not the one") {
		t.Fatalf("a callback without the binding cookie: %q", reason(t, server))
	}
	// The request is used once: its answer again is refused.
	binding = start(t, server)
	get(t, server, callbackPath+"?error=access_denied&state=s1", binding)
	if r := reason(t, server); !strings.Contains(r, "IdMS refused the sign-in (access_denied)") {
		t.Fatalf("IdMS refusal: %q", r)
	}
	if get(t, server, callbackPath+"?code=good&state=s1", binding); !strings.Contains(reason(t, server), "not the one") {
		t.Fatalf("a request answered twice: %q", reason(t, server))
	}

	binding = start(t, server)
	res := get(t, server, callbackPath+"?code=good&state=s1", binding)
	session := cookieNamed(res, sessionCookie)
	if res.StatusCode != http.StatusSeeOther || res.Header.Get("Location") != "/" || session == nil {
		t.Fatalf("callback: %d %q, reason %q", res.StatusCode, res.Header.Get("Location"), reason(t, server))
	}
	if status, info, _ := call(t, server, "GET", "/api/session", session.Value, ""); status != http.StatusOK ||
		info["signed_in"] != true || info["operator"] != "sip:d1.mcptt@example.org" {
		t.Fatalf("session after sign-in: %d %v", status, info)
	}
	// The running configuration keeps neither the code nor a refresh token file.
	kept, err := os.ReadFile(filepath.Join(dir, "run", "engine.json"))
	if err != nil || strings.Contains(string(kept), "authorization") || strings.Contains(string(kept), "refresh_token_file") {
		t.Fatalf("runtime configuration: %s %v", kept, err)
	}
	// A second browser cannot start a sign-in while an operator is signed in.
	get(t, server, "/api/login/browser")
	if r := reason(t, server); !strings.Contains(r, "must sign out first") {
		t.Fatalf("second sign-in: %q", r)
	}
	// IdMS refusing the refresh token ends the session.
	if err := pos.engine.command("refuse"); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		if running, _ := pos.engine.running(); !running {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the session outlived IdMS's refusal")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func TestBrowserSignInNeedsTheCallback(t *testing.T) {
	dir := t.TempDir()
	config := filepath.Join(dir, "config.json")
	_ = os.WriteFile(config, []byte(`{"oauth":{"login":"browser","redirect_uri":"http://127.0.0.1:8080/callback"}}`), 0o600)
	if _, err := browserSignIn(config); err == nil {
		t.Fatal("a redirect URI other than the callback was accepted")
	}
	_ = os.WriteFile(config, []byte(`{"oauth":{"username":"d1","password":"right"}}`), 0o600)
	if browser, err := browserSignIn(config); browser || err != nil {
		t.Fatalf("a password configuration: %v %v", browser, err)
	}
}
