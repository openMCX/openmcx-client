//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Command mcx-agent gives an AI agent MCData one-to-one short data (SDS) and file
// distribution (FD), MCPTT voice (the talk bursts it hears, the speech it says) and MCVideo
// (the pictures it streams, transmitted in calls) through the MCX client engine (mcx-client).
// One binary, three modes:
//
//   - mcx-agent daemon --config <agent.json>: runs the engine for good, activates the
//     agent's functional alias, keeps every message in its own store and serves the tools
//     on a unix socket (owner only);
//   - mcx-agent mcp: an MCP server on stdin and stdout whose tools are the daemon's;
//   - mcx-agent <tool> [--<argument> <value>]...: one tool call from a shell, JSON out.
//
// The CLI and the MCP server are clients of the daemon, so a tool behaves the same in both.
// Group SDS is outside the agent's reach.
package main

import (
	"fmt"
	"os"
)

const defaultSocket = "/run/mcx-agent/agent.sock"

// version is the version of the API contract (API.md): the tools, their arguments and results,
// the error codes. A tool or argument added raises the minor version; one changed or removed
// raises the major. tools_snapshot.json holds the tools of this version.
const version = "1.2.0"

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "daemon":
		os.Exit(runDaemon(os.Args[2:]))
	case "mcp":
		os.Exit(runMCP(os.Args[2:]))
	case "-h", "--help", "help":
		usage()
	default:
		os.Exit(runCLI(os.Args[1:]))
	}
}

func usage() {
	fmt.Fprintf(os.Stderr, `usage:
  mcx-agent daemon --config <agent.json>
  mcx-agent mcp [--socket <path>]
  mcx-agent [--socket <path>] <tool> [--<argument> <value>]...
  mcx-agent [--socket <path>] video-feed [--file <path>|-] [--pace]
  mcx-agent tools [--json]

The socket is %s unless --socket or MCX_AGENT_SOCKET says otherwise.
`, defaultSocket)
}

// socketPath is --socket when given, then MCX_AGENT_SOCKET, then the default.
func socketPath(flagValue string) string {
	if flagValue != "" {
		return flagValue
	}
	if env := os.Getenv("MCX_AGENT_SOCKET"); env != "" {
		return env
	}
	return defaultSocket
}
