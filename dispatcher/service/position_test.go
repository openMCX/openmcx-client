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

// A position with the fake engine (testdata/fake-engine) behind the API.
func testPosition(t *testing.T) (*httptest.Server, *position, string) {
	t.Helper()
	dir := t.TempDir()
	run := filepath.Join(dir, "run")
	if err := os.MkdirAll(run, 0o700); err != nil {
		t.Fatal(err)
	}
	device := filepath.Join(dir, "device.json")
	settings := map[string]any{"control_fifo": filepath.Join(run, "control"), "status_file": filepath.Join(run, "status.json"),
		"media": map[string]any{"live": map[string]any{"port": 0, "send_port": 0}}}
	raw, _ := json.Marshal(settings)
	if err := os.WriteFile(device, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	fake, _ := filepath.Abs("testdata/fake-engine")
	e, err := newEngine(fake, device, nil)
	if err != nil {
		t.Fatal(err)
	}
	pos, err := newPosition(e, device, filepath.Join(dir, "data"), 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	(&api{engine: e, outbox: filepath.Join(dir, "outbox"), pos: pos}).routes(mux)
	server := httptest.NewServer(mux)
	t.Cleanup(func() { e.stop(); server.Close() })
	return server, pos, dir
}

func call(t *testing.T, server *httptest.Server, method, path, cookie, body string) (int, map[string]any, []*http.Cookie) {
	t.Helper()
	req, _ := http.NewRequest(method, server.URL+path, strings.NewReader(body))
	req.Header.Set("X-Dispatcher", "1")
	req.Header.Set("Content-Type", "application/json")
	if cookie != "" {
		req.AddCookie(&http.Cookie{Name: sessionCookie, Value: cookie})
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out, res.Cookies()
}

func sessionCookieOf(cookies []*http.Cookie) string {
	for _, c := range cookies {
		if c.Name == sessionCookie {
			return c.Value
		}
	}
	return ""
}

func TestPositionSignInAndOut(t *testing.T) {
	server, pos, dir := testPosition(t)

	if status, _, _ := call(t, server, "GET", "/api/state", "", ""); status != http.StatusUnauthorized {
		t.Fatalf("state without a session: %d", status)
	}
	if status, _, _ := call(t, server, "POST", "/api/login", "", `{"username":"d1","password":"right"}`); status != http.StatusNotFound {
		t.Fatalf("sign-in before activation: %d", status)
	}
	if status, _, _ := call(t, server, "POST", "/api/activate", "", `{"url":"https://mcxadmin/activate/x","password":"wrong"}`); status != http.StatusBadRequest {
		t.Fatalf("activation with a wrong password: %d", status)
	}
	status, _, cookies := call(t, server, "POST", "/api/activate", "", `{"url":"https://mcxadmin/activate/x","password":"right"}`)
	cookie := sessionCookieOf(cookies)
	if status != http.StatusOK || cookie == "" {
		t.Fatalf("activation: %d, cookie %q", status, cookie)
	}
	// The account is kept, without its password.
	dirOf := pos.accounts()["d1"]
	kept, err := os.ReadFile(filepath.Join(dirOf, "config.json"))
	if err != nil || strings.Contains(string(kept), "right") || !strings.Contains(string(kept), `"username": "d1"`) {
		t.Fatalf("kept configuration: %s %v", kept, err)
	}
	if status, view, _ := call(t, server, "GET", "/api/state", cookie, ""); status != http.StatusOK || view["authorized"] != true {
		t.Fatalf("state signed in: %d %v", status, view)
	}
	if status, _, _ := call(t, server, "GET", "/api/state", "forged", ""); status != http.StatusUnauthorized {
		t.Fatalf("state with another cookie: %d", status)
	}
	// A second browser cannot sign in while an operator is.
	if status, _, _ := call(t, server, "POST", "/api/login", "", `{"username":"d1","password":"right"}`); status != http.StatusConflict {
		t.Fatalf("second sign-in: %d", status)
	}
	if status, _, _ := call(t, server, "POST", "/api/logout", cookie, ""); status != http.StatusOK {
		t.Fatalf("sign-out: %d", status)
	}
	if running, _ := pos.engine.running(); running {
		t.Fatal("the engine runs after sign-out")
	}
	if _, err := os.Stat(filepath.Join(dir, "run", "engine.json")); err == nil {
		t.Fatal("the runtime configuration outlives the session")
	}
	if status, _, _ := call(t, server, "GET", "/api/state", cookie, ""); status != http.StatusUnauthorized {
		t.Fatalf("state after sign-out: %d", status)
	}
	if status, _, _ := call(t, server, "POST", "/api/login", "", `{"username":"d1","password":"wrong"}`); status != http.StatusUnauthorized {
		t.Fatalf("sign-in with a wrong password: %d", status)
	}
	status, _, cookies = call(t, server, "POST", "/api/login", "", `{"username":"d1","password":"right"}`)
	if cookie = sessionCookieOf(cookies); status != http.StatusOK || cookie == "" {
		t.Fatalf("sign-in: %d", status)
	}
	runtime, err := os.Stat(filepath.Join(dir, "run", "engine.json"))
	if err != nil || runtime.Mode().Perm() != 0o600 {
		t.Fatalf("runtime configuration: %v %v", runtime, err)
	}
	// Signed in, the runtime configuration no longer holds the password.
	if kept, _ := os.ReadFile(filepath.Join(dir, "run", "engine.json")); strings.Contains(string(kept), `"right"`) {
		t.Fatalf("the runtime configuration keeps the password: %s", kept)
	}

	// IdMS refusing the token renewal ends the session, with a reason.
	if err := pos.engine.command("refuse"); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		_, info, _ := call(t, server, "GET", "/api/session", cookie, "")
		if info["signed_in"] == false && strings.Contains(info["reason"].(string), "IdMS no longer accepts") {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("session after IdMS refused the renewal: %v", info)
		}
		time.Sleep(100 * time.Millisecond)
	}
	if running, _ := pos.engine.running(); running {
		t.Fatal("the engine runs after the session ended")
	}

	// A session whose page has gone ends by itself (idle-signout, 3 s here).
	status, _, cookies = call(t, server, "POST", "/api/login", "", `{"username":"d1","password":"right"}`)
	if status != http.StatusOK {
		t.Fatalf("sign-in: %d", status)
	}
	time.Sleep(5 * time.Second)
	if _, info, _ := call(t, server, "GET", "/api/session", sessionCookieOf(cookies), ""); info["signed_in"] != false ||
		!strings.Contains(info["reason"].(string), "No operator page") {
		t.Fatalf("session after its page went: %v", info)
	}
}

func TestRefusalsEndTheSession(t *testing.T) {
	for reason, ends := range map[string]bool{
		"token renewal: IdMS user authentication failed":                               true,
		"token renewal: IdMS refused the refresh token, and no password is configured": true,
		"token renewal: connection refused":                                            false, // IdMS unreachable: keep trying
		"SIP registration: 401":                                                        true,
		"SIP registration: 403":                                                        true,
		"SIP registration: 408":                                                        false,
		"service authorization renewal: 403":                                           true,
		"PTT requires active call":                                                     false,
	} {
		if got := refusedAtIdMS.MatchString(reason) || refusedAtCore.MatchString(reason); got != ends {
			t.Errorf("%q ends the session: %v, want %v", reason, got, ends)
		}
	}
}
