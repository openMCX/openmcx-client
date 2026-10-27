//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"slices"
	"strings"
)

// The MCP streamable HTTP transport (MCP 2025-06-18, "Transports"), for an agent in another
// container: one endpoint, /mcp. A POST carries one JSON-RPC message (or, as MCP 2025-03-26
// allows, a batch); a request is answered with application/json, a notification with 202. The
// server sends nothing on its own, so a GET (the stream for server messages) is 405, and it
// keeps no session. Every request needs the bearer token; a request from a web page (an Origin
// header) needs that origin in allowed_origins, against DNS rebinding.

// mcpHTTPConfig is the daemon configuration's "mcp_http".
type mcpHTTPConfig struct {
	Listen         string   `json:"listen"`          // e.g. 0.0.0.0:8765, on the compose or pod network only
	TokenFile      string   `json:"token_file"`      // the bearer token, at least 32 characters
	AllowedOrigins []string `json:"allowed_origins"` // web pages that may call it; none by default
}

const minTokenLength = 32

// readToken reads the bearer token: the file's first line, at least 32 characters.
func readToken(file string) (string, error) {
	raw, err := os.ReadFile(file)
	if err != nil {
		return "", err
	}
	token := strings.TrimSpace(strings.SplitN(string(raw), "\n", 2)[0])
	if len(token) < minTokenLength {
		return "", fmt.Errorf("%s: the token needs at least %d characters", file, minTokenLength)
	}
	return token, nil
}

func newMCPHTTP(token string, origins []string, call toolCaller) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/mcp", func(w http.ResponseWriter, r *http.Request) {
		if origin := r.Header.Get("Origin"); origin != "" && !slices.Contains(origins, origin) {
			http.Error(w, "origin not allowed", http.StatusForbidden)
			return
		}
		given, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !found || subtle.ConstantTimeCompare([]byte(given), []byte(token)) != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="mcx-agent"`)
			http.Error(w, "a bearer token is needed", http.StatusUnauthorized)
			return
		}
		switch r.Method {
		case http.MethodPost:
		case http.MethodGet, http.MethodDelete:
			w.Header().Set("Allow", "POST")
			http.Error(w, "this server sends no messages of its own and keeps no session", http.StatusMethodNotAllowed)
			return
		default:
			w.Header().Set("Allow", "POST")
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
		if err != nil {
			http.Error(w, err.Error(), http.StatusRequestEntityTooLarge)
			return
		}
		replies, batch, err := answerHTTP(raw, call)
		if err != nil {
			writeJSONRPC(w, http.StatusBadRequest, rpcReply(json.RawMessage("null"), nil, &rpcError{-32700, err.Error()}))
			return
		}
		if len(replies) == 0 {
			w.WriteHeader(http.StatusAccepted) // notifications only
			return
		}
		if batch {
			writeJSONRPC(w, http.StatusOK, replies)
			return
		}
		writeJSONRPC(w, http.StatusOK, replies[0])
	})
	return mux
}

// answerHTTP answers the body of a POST: one message or a batch (a JSON array).
func answerHTTP(raw []byte, call toolCaller) ([]map[string]any, bool, error) {
	var messages []rpcMessage
	trimmed := strings.TrimSpace(string(raw))
	batch := trimmed != "" && trimmed[0] == '['
	if batch {
		if err := json.Unmarshal(raw, &messages); err != nil {
			return nil, true, errors.New("parse error")
		}
	} else {
		var msg rpcMessage
		if err := json.Unmarshal(raw, &msg); err != nil {
			return nil, false, errors.New("parse error")
		}
		messages = []rpcMessage{msg}
	}
	var replies []map[string]any
	for _, msg := range messages {
		if reply := handleRPC(msg, call); reply != nil {
			replies = append(replies, reply)
		}
	}
	return replies, batch, nil
}

func writeJSONRPC(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
