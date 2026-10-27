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

// The MCP server: the daemon's tools and nothing else. Two transports share handleRPC: stdio
// (mcx-agent mcp, JSON-RPC 2.0 messages one per line, through the daemon's socket) and
// streamable HTTP (mcphttp.go, in the daemon). On stdio each tools/call runs concurrently, so
// that a wait_message does not hold back a send_message; on HTTP each request is its own.

var mcpVersions = []string{"2025-06-18", "2025-03-26", "2024-11-05"}

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

type toolCaller func(string, map[string]any) (any, error)

// rpcReply is a JSON-RPC response to the request id.
func rpcReply(id json.RawMessage, result any, rpcErr *rpcError) map[string]any {
	reply := map[string]any{"jsonrpc": "2.0", "id": id}
	if rpcErr != nil {
		reply["error"] = rpcErr
	} else {
		reply["result"] = result
	}
	return reply
}

// handleRPC answers one MCP message; nil for a notification (notifications/initialized,
// notifications/cancelled), which has no answer.
func handleRPC(msg rpcMessage, call toolCaller) map[string]any {
	if msg.ID == nil {
		return nil
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
		return rpcReply(msg.ID, map[string]any{
			"protocolVersion": chosen,
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": "mcx-agent", "version": version},
			"instructions": "MCData one-to-one short data and files through an MC system, as the agent's functional " +
				"alias. Call status first. Received messages: inbox to read, wait_message to wait for the next one; " +
				"mark_read sends the read report. Text may have line breaks. A failed call says <code>: <message>.",
		}, nil)
	case "ping":
		return rpcReply(msg.ID, map[string]any{}, nil)
	case "tools/list":
		return rpcReply(msg.ID, map[string]any{"tools": toolList()}, nil)
	case "tools/call":
		var p struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		}
		if err := json.Unmarshal(msg.Params, &p); err != nil {
			return rpcReply(msg.ID, nil, &rpcError{-32602, "invalid params"})
		}
		if findTool(p.Name) == nil {
			return rpcReply(msg.ID, nil, &rpcError{-32602, "unknown tool " + p.Name})
		}
		result, err := call(p.Name, p.Arguments)
		return rpcReply(msg.ID, toolResult(result, err), nil)
	default:
		return rpcReply(msg.ID, nil, &rpcError{-32601, "method not found: " + msg.Method})
	}
}

// toolList is the tools as MCP lists them: name, description and input schema.
func toolList() []map[string]any {
	list := make([]map[string]any, 0, len(tools))
	for i := range tools {
		t := &tools[i]
		list = append(list, map[string]any{"name": t.name, "description": t.description, "inputSchema": t.schema()})
	}
	return list
}

// serveMCP answers MCP requests from in on out until in ends (the stdio transport).
func serveMCP(in io.Reader, out io.Writer, call toolCaller) error {
	var mu sync.Mutex
	var running sync.WaitGroup
	send := func(reply map[string]any) {
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
			send(rpcReply(json.RawMessage("null"), nil, &rpcError{-32700, "parse error"}))
			continue
		}
		running.Add(1)
		go func() {
			defer running.Done()
			if reply := handleRPC(msg, call); reply != nil {
				send(reply)
			}
		}()
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
