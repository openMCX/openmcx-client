//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
)

// daemonClient calls the daemon's tools over its unix socket.
type daemonClient struct{ http *http.Client }

func newDaemonClient(socket string) *daemonClient {
	dial := func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", socket)
	}
	return &daemonClient{http: &http.Client{Transport: &http.Transport{DialContext: dial}}}
}

func (c *daemonClient) call(tool string, args map[string]any) (any, error) {
	if args == nil {
		args = map[string]any{}
	}
	body, _ := json.Marshal(map[string]any{"tool": tool, "args": args})
	resp, err := c.http.Post("http://mcx-agent/call", "application/json", bytes.NewReader(body))
	if err != nil {
		return nil, fail(codeNotReady, "the mcx-agent daemon is not reachable: %v", err)
	}
	defer resp.Body.Close()
	var reply struct {
		OK     bool   `json:"ok"`
		Result any    `json:"result"`
		Error  string `json:"error"`
		Code   string `json:"code"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&reply); err != nil {
		return nil, err
	}
	if !reply.OK {
		return nil, &toolError{code: reply.Code, message: reply.Error}
	}
	return reply.Result, nil
}

// runCLI runs one tool: mcx-agent [--socket <path>] <tool> [--<argument> <value>]... and
// prints its result as JSON, or {"ok": false, "error", "code"}. Exit status 0 when the tool
// succeeded; on failure 2 for a usage error, 3 refused by the agent's policy, 4 the engine or
// daemon not ready, 1 otherwise (exitStatus).
func runCLI(argv []string) int {
	socket := ""
	if len(argv) >= 2 && argv[0] == "--socket" {
		socket, argv = argv[1], argv[2:]
	}
	if len(argv) == 0 {
		usage()
		return 2
	}
	if argv[0] == "tools" {
		printTools()
		return 0
	}
	t := findTool(strings.ReplaceAll(argv[0], "-", "_"))
	if t == nil {
		fmt.Fprintf(os.Stderr, "unknown tool %q; mcx-agent tools lists them\n", argv[0])
		return 2
	}
	args, err := parseFlags(t, argv[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 2
	}
	result, err := newDaemonClient(socketPath(socket)).call(t.name, args)
	out := json.NewEncoder(os.Stdout)
	out.SetIndent("", "  ")
	if err != nil {
		code := errorCode(err)
		_ = out.Encode(map[string]any{"ok": false, "error": err.Error(), "code": code})
		return exitStatus(code)
	}
	_ = out.Encode(result)
	return 0
}

// parseFlags turns --name value, --name=value and a bare --flag (true) into the tool's
// arguments, typed by its parameters.
func parseFlags(t *tool, argv []string) (map[string]any, error) {
	args := map[string]any{}
	for i := 0; i < len(argv); i++ {
		word, ok := strings.CutPrefix(argv[i], "--")
		if !ok {
			return nil, fmt.Errorf("unexpected %q: arguments are --name value", argv[i])
		}
		name, value, inline := strings.Cut(word, "=")
		name = strings.ReplaceAll(name, "-", "_")
		var p *param
		for j := range t.params {
			if t.params[j].name == name {
				p = &t.params[j]
			}
		}
		if p == nil {
			return nil, fmt.Errorf("%s has no argument --%s", t.name, name)
		}
		if !inline {
			if p.kind == "boolean" && (i+1 == len(argv) || strings.HasPrefix(argv[i+1], "--")) {
				value = "true"
			} else if i+1 < len(argv) {
				i++
				value = argv[i]
			} else {
				return nil, fmt.Errorf("--%s needs a value", name)
			}
		}
		switch p.kind {
		case "integer":
			n, err := strconv.Atoi(value)
			if err != nil {
				return nil, fmt.Errorf("--%s is an integer", name)
			}
			args[name] = float64(n)
		case "boolean":
			b, err := strconv.ParseBool(value)
			if err != nil {
				return nil, fmt.Errorf("--%s is true or false", name)
			}
			args[name] = b
		default:
			args[name] = value
		}
	}
	return args, nil
}

func printTools() {
	names := make([]string, 0, len(tools))
	for _, t := range tools {
		names = append(names, t.name)
	}
	sort.Strings(names)
	for _, name := range names {
		t := findTool(name)
		fmt.Printf("%s\n    %s\n", t.name, t.description)
		for _, p := range t.params {
			extra := ""
			if p.required {
				extra = ", required"
			} else if p.def != nil {
				extra = fmt.Sprintf(", default %v", p.def)
			}
			if p.enum != nil {
				extra += ", one of " + strings.Join(p.enum, "|")
			}
			fmt.Printf("    --%s (%s%s): %s\n", p.name, p.kind, extra, p.description)
		}
	}
}
