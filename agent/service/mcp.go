//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"io"
	"log"
	"os"
	"slices"
	"sync"
)

// The MCP server: JSON-RPC 2.0 messages, one per line, on stdin and stdout (the MCP stdio
// transport). It offers the daemon's tools and nothing else; each tools/call is one call to
// the daemon, run concurrently so that a wait_message does not hold back a send_message.

var mcpVersions = []string{"2025-06-18", "2025-03-26", "2024-11-05"}

const version = "0.1"

func runMCP(args []string) int {
	flags := flag.NewFlagSet("mcp", flag.ContinueOnError)
	socket := flags.String("socket", "", "the daemon's socket")
	if flags.Parse(args) != nil {
		return 2
	}
	client := newDaemonClient(socketPath(*socket))
	if err := serveMCP(os.Stdin, os.Stdout, client.call); err != nil {
		log.Print(err)
		return 1
	}
	return 0
}

type rpcMessage struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// serveMCP answers MCP requests from in on out until in ends.
func serveMCP(in io.Reader, out io.Writer, call func(string, map[string]any) (any, error)) error {
	var mu sync.Mutex
	var running sync.WaitGroup
	send := func(id json.RawMessage, result any, rpcErr *rpcError) {
		reply := map[string]any{"jsonrpc": "2.0", "id": id}
		if rpcErr != nil {
			reply["error"] = rpcErr
		} else {
			reply["result"] = result
		}
		line, _ := json.Marshal(reply)
		mu.Lock()
		defer mu.Unlock()
		_, _ = out.Write(append(line, '\n'))
	}
	scanner := bufio.NewScanner(in)
	scanner.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for scanner.Scan() {
		var msg rpcMessage
		if err := json.Unmarshal(scanner.Bytes(), &msg); err != nil {
			send(json.RawMessage("null"), nil, &rpcError{-32700, "parse error"})
			continue
		}
		if msg.ID == nil { // a notification: notifications/initialized, notifications/cancelled
			continue
		}
		switch msg.Method {
		case "initialize":
			var p struct {
				ProtocolVersion string `json:"protocolVersion"`
			}
			_ = json.Unmarshal(msg.Params, &p)
			chosen := mcpVersions[0]
			if slices.Contains(mcpVersions, p.ProtocolVersion) {
				chosen = p.ProtocolVersion
			}
			send(msg.ID, map[string]any{
				"protocolVersion": chosen,
				"capabilities":    map[string]any{"tools": map[string]any{}},
				"serverInfo":      map[string]any{"name": "mcx-agent", "version": version},
				"instructions": "MCData one-to-one short data and files through an MC system. Call status first. " +
					"Received messages: inbox to read, wait_message to wait for the next one; mark_read sends " +
					"the read report. Messages are one line of text.",
			}, nil)
		case "ping":
			send(msg.ID, map[string]any{}, nil)
		case "tools/list":
			list := make([]map[string]any, 0, len(tools))
			for i := range tools {
				t := &tools[i]
				list = append(list, map[string]any{"name": t.name, "description": t.description, "inputSchema": t.schema()})
			}
			send(msg.ID, map[string]any{"tools": list}, nil)
		case "tools/call":
			var p struct {
				Name      string         `json:"name"`
				Arguments map[string]any `json:"arguments"`
			}
			if err := json.Unmarshal(msg.Params, &p); err != nil {
				send(msg.ID, nil, &rpcError{-32602, "invalid params"})
				continue
			}
			if findTool(p.Name) == nil {
				send(msg.ID, nil, &rpcError{-32602, "unknown tool " + p.Name})
				continue
			}
			running.Add(1)
			go func(id json.RawMessage) {
				defer running.Done()
				result, err := call(p.Name, p.Arguments)
				send(id, toolResult(result, err), nil)
			}(msg.ID)
		default:
			send(msg.ID, nil, &rpcError{-32601, "method not found: " + msg.Method})
		}
	}
	running.Wait()
	return scanner.Err()
}

// toolResult is an MCP CallToolResult: the result as JSON text and as structured content,
// or the error ("<code>: <message>", and {error, code} as structured content) with isError.
func toolResult(result any, err error) map[string]any {
	if err != nil {
		code := errorCode(err)
		return map[string]any{
			"content":           []any{map[string]any{"type": "text", "text": code + ": " + err.Error()}},
			"structuredContent": map[string]any{"error": err.Error(), "code": code},
			"isError":           true,
		}
	}
	text, _ := json.Marshal(result)
	out := map[string]any{"content": []any{map[string]any{"type": "text", "text": string(text)}}, "isError": false}
	if _, ok := result.(map[string]any); ok {
		out["structuredContent"] = result
	}
	return out
}
