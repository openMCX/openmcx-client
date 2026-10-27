//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"testing"
)

func TestMCP(t *testing.T) {
	requests := strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}`,
		`{"jsonrpc":"2.0","method":"notifications/initialized"}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/list"}`,
		`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"status","arguments":{}}}`,
		`{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"send_message","arguments":{"to":"sip:x@y"}}}`,
		`{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}`,
		`{"jsonrpc":"2.0","id":6,"method":"resources/list"}`,
		`not json`,
	}, "\n")
	call := func(tool string, args map[string]any) (any, error) {
		if tool == "status" {
			return map[string]any{"can_send": true}, nil
		}
		return nil, errors.New("send_message needs \"text\"")
	}
	reader, writer := io.Pipe()
	go func() {
		_ = serveMCP(strings.NewReader(requests), writer, call)
		writer.Close()
	}()
	replies := map[string]map[string]any{}
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 1<<20), 1<<20)
	for scanner.Scan() {
		var reply map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &reply); err != nil {
			t.Fatal(err)
		}
		id, _ := json.Marshal(reply["id"])
		replies[string(id)] = reply
	}
	result := func(id string) map[string]any { r, _ := replies[id]["result"].(map[string]any); return r }
	if r := result("1"); r["protocolVersion"] != "2025-03-26" || r["serverInfo"].(map[string]any)["name"] != "mcx-agent" {
		t.Fatalf("initialize %v", replies["1"])
	}
	list := result("2")["tools"].([]any)
	if len(list) != len(tools) {
		t.Fatalf("tools/list %v", list)
	}
	for _, item := range list {
		tool := item.(map[string]any)
		if tool["name"] == "send_message" {
			schema := tool["inputSchema"].(map[string]any)
			if req := schema["required"].([]any); len(req) != 2 || schema["additionalProperties"] != false {
				t.Fatalf("send_message schema %v", schema)
			}
		}
	}
	if r := result("3"); r["isError"] != false || r["structuredContent"].(map[string]any)["can_send"] != true {
		t.Fatalf("status call %v", replies["3"])
	}
	if r := result("4"); r["isError"] != true || !strings.Contains(r["content"].([]any)[0].(map[string]any)["text"].(string), "text") {
		t.Fatalf("failed call %v", replies["4"])
	}
	for _, id := range []string{"5", "6", "null"} {
		if replies[id]["error"] == nil {
			t.Fatalf("reply %s: %v", id, replies[id])
		}
	}
}

func TestParseFlags(t *testing.T) {
	send := findTool("send_message")
	args, err := parseFlags(send, []string{"--to", alice, "--text=hello there", "--timeout-s", "5"})
	if err != nil || args["to"] != alice || args["text"] != "hello there" || args["timeout_s"] != 5.0 {
		t.Fatalf("%v %v", args, err)
	}
	if _, err := send.bind(args); err != nil {
		t.Fatal(err)
	}
	file := findTool("send_file")
	args, err = parseFlags(file, []string{"--mandatory", "--to", alice, "--file", "a.pdf"})
	if err != nil || args["mandatory"] != true {
		t.Fatalf("%v %v", args, err)
	}
	for _, bad := range [][]string{{"--nope", "1"}, {"--timeout-s", "soon"}, {"positional"}, {"--to"}} {
		if _, err := parseFlags(send, bad); err == nil {
			t.Fatalf("%v: no error", bad)
		}
	}
}
