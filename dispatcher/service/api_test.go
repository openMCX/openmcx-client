package main

import "testing"

func TestCommandLine(t *testing.T) {
	good := []struct {
		req  map[string]any
		want string
	}{
		{map[string]any{"action": "call"}, "call"},
		{map[string]any{"action": "call", "kind": "emergency"}, "call emergency"},
		{map[string]any{"action": "private", "target": "sip:bob@example.org", "emergency": true}, "private sip:bob@example.org emergency"},
		{map[string]any{"action": "ptt"}, "ptt live"},
		{map[string]any{"action": "release"}, "release"},
		{map[string]any{"action": "alert", "on": true}, "alert on"},
		{map[string]any{"action": "sds", "target": "sip:g1@example.org", "group": true, "text": "all units report"}, "sds-group sip:g1@example.org all units report"},
		{map[string]any{"action": "fd-accept", "id": "3f2a-11"}, "fd-accept 3f2a-11"},
	}
	for _, c := range good {
		got, err := commandLine(c.req)
		if err != nil || got != c.want {
			t.Errorf("%v: got %q, %v; want %q", c.req, got, err, c.want)
		}
	}
	bad := []map[string]any{
		{"action": "quit"},
		{"action": "call", "kind": "force"},
		{"action": "private", "target": "bob quit"},
		{"action": "sds", "target": "sip:a@b", "text": "one\nquit"},
		{"action": "sds", "target": "sip:a@b", "text": "  "},
		{"action": "fd-accept", "id": ""},
	}
	for _, req := range bad {
		if got, err := commandLine(req); err == nil {
			t.Errorf("%v: accepted as %q", req, got)
		}
	}
}
