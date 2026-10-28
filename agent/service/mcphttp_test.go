//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testToken = "0123456789abcdef0123456789abcdef-test"

func TestMCPHTTP(t *testing.T) {
	call := func(tool string, args map[string]any) (any, error) {
		if tool == "status" {
			return map[string]any{"can_send": true}, nil
		}
		return nil, fail(codeNotAllowed, "sip:x@y is not among the recipients the agent may send to")
	}
	server := httptest.NewServer(newMCPHTTP(testToken, []string{"https://ops.example.org"}, call, nil))
	defer server.Close()
	post := func(body, token, origin string) (*http.Response, string) {
		req, _ := http.NewRequest(http.MethodPost, server.URL+"/mcp", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json, text/event-stream")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		return resp, string(raw)
	}
	initialize := `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`

	if resp, _ := post(initialize, "", ""); resp.StatusCode != 401 || resp.Header.Get("WWW-Authenticate") == "" {
		t.Fatalf("no token: %d", resp.StatusCode)
	}
	if resp, _ := post(initialize, "wrong-token-wrong-token-wrong-token", ""); resp.StatusCode != 401 {
		t.Fatalf("wrong token: %d", resp.StatusCode)
	}
	if resp, _ := post(initialize, testToken, "https://evil.example.com"); resp.StatusCode != 403 {
		t.Fatalf("foreign origin: %d", resp.StatusCode)
	}
	resp, body := post(initialize, testToken, "https://ops.example.org")
	var reply map[string]any
	_ = json.Unmarshal([]byte(body), &reply)
	result, _ := reply["result"].(map[string]any)
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "application/json" ||
		result["serverInfo"].(map[string]any)["version"] != version {
		t.Fatalf("initialize: %d %s", resp.StatusCode, body)
	}
	if resp, _ := post(`{"jsonrpc":"2.0","method":"notifications/initialized"}`, testToken, ""); resp.StatusCode != 202 {
		t.Fatalf("notification: %d", resp.StatusCode)
	}
	_, body = post(`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"send_message","arguments":{}}}`, testToken, "")
	if !strings.Contains(body, `"isError":true`) || !strings.Contains(body, `"code":"not_allowed"`) {
		t.Fatalf("failed tool: %s", body)
	}
	_, body = post(`[{"jsonrpc":"2.0","id":3,"method":"ping"},{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"status","arguments":{}}}]`, testToken, "")
	var batch []map[string]any
	if json.Unmarshal([]byte(body), &batch) != nil || len(batch) != 2 {
		t.Fatalf("batch: %s", body)
	}
	if resp, _ := post("not json", testToken, ""); resp.StatusCode != 400 {
		t.Fatalf("parse error: %d", resp.StatusCode)
	}
	req, _ := http.NewRequest(http.MethodGet, server.URL+"/mcp", nil)
	req.Header.Set("Authorization", "Bearer "+testToken)
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 405 {
		t.Fatalf("GET: %v %v", resp, err)
	}
}

func TestReadToken(t *testing.T) {
	dir := t.TempDir()
	short := filepath.Join(dir, "short")
	_ = os.WriteFile(short, []byte("too-short\n"), 0o600)
	if _, err := readToken(short); err == nil {
		t.Fatal("a short token was taken")
	}
	good := filepath.Join(dir, "good")
	_ = os.WriteFile(good, []byte(testToken+"\nignored\n"), 0o600)
	if token, err := readToken(good); err != nil || token != testToken {
		t.Fatalf("%q %v", token, err)
	}
	if _, err := readToken(filepath.Join(dir, "missing")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("missing: %v", err)
	}
}
