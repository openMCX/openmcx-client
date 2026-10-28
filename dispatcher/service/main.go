//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Command dispatcher is the OpenMCX dispatcher position: a web service that runs the MCX
// client engine (mcx-client) as a child process and gives a browser its calls, push to
// talk with live voice, short data (SDS) and file distribution (FD). It reuses what the
// OpenMCX servers implement; nothing on the servers is specific to it.
//
// Two modes:
//   - lab mode (--config): the engine runs for good with a full configuration, as a lab client; or,
//     with oauth.login "browser", only while an operator is signed in on IdMS's own page (login.go);
//   - position mode (--device, --data): accounts are activated on the position from MCX Admin
//     activation links, an operator signs in with an activated account, and the engine runs
//     only while someone is signed in (see position.go).
//
// The browser talks to this service only: HTTP for commands and uploads, server-sent
// events for the engine's state, and a WebSocket for audio (16 kHz 16-bit mono PCM both
// ways, bridged to the engine's media.live UDP ports). It listens on localhost by default:
// the browser must reach it from a secure context (https or localhost) to use the microphone.
package main

import (
	"embed"
	"flag"
	"io/fs"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

//go:embed static
var static embed.FS

func main() {
	listen := flag.String("listen", "127.0.0.1:8080", "address of the web UI (localhost: a secure context for the microphone)")
	path := flag.String("engine", "/usr/local/bin/mcx-client", "the MCX client engine")
	config := flag.String("config", "", "lab mode: the engine's full configuration (with media.live)")
	device := flag.String("device", "", "position mode: the position's device settings, no identity (with media.live)")
	data := flag.String("data", "", "position mode: where activated accounts are kept")
	engineArgs := flag.String("engine-args", "", "further engine arguments, space-separated (e.g. --disable-tls)")
	outbox := flag.String("outbox", "/data/outbox", "where files to send are kept")
	idle := flag.Duration("idle-signout", 2*time.Minute, "position mode: sign out when no operator page has been open this long")
	opsAgent := flag.String("ops-agent", "", "the ops agent the client UI's chat offers first: its functional alias or MCData ID")
	opsAgentName := flag.String("ops-agent-name", "Ops agent", "the ops agent's name in the chat")
	opsAgentAlias := flag.Bool("ops-agent-alias", true, "--ops-agent is a functional alias (false: an MCData ID)")
	opsAgentIntro := flag.String("ops-agent-intro", "Ask {name} about incidents, units and procedures.",
		"the line over an empty chat with the ops agent; {name} is its name")
	opsAgentSuggestions := flag.String("ops-agent-suggestions",
		"What can you help me with?|Any open incidents near me?|Which units are available?",
		"the questions the empty chat offers, separated by |; empty: none")
	flag.Parse()
	if *opsAgent != "" && !word.MatchString(*opsAgent) {
		log.Fatal("--ops-agent must be a SIP URI")
	}

	base := *config
	if (*config == "") == (*device == "") || (*device != "") != (*data != "") {
		log.Fatal("give --config (lab mode), or --device and --data (position mode)")
	}
	if base == "" {
		base = *device
	}
	e, err := newEngine(*path, base, strings.Fields(*engineArgs))
	if err != nil {
		log.Fatal(err)
	}
	var pos *position
	browser, err := browserSignIn(*config)
	if err != nil {
		log.Fatal(err)
	}
	switch {
	case *device != "":
		if pos, err = newPosition(e, *device, *data, *idle); err != nil {
			log.Fatal(err)
		}
	case browser:
		pos = newBrowserPosition(e, *config, *idle)
	default:
		go e.supervise()
	}
	audio, err := newAudioBridge(e.liveIn, e.liveOut)
	if err != nil {
		log.Fatal(err)
	}
	if err := os.MkdirAll(*outbox, 0o700); err != nil {
		log.Fatal(err)
	}
	api := &api{engine: e, audio: audio, outbox: *outbox, pos: pos}
	if e.videoOut != "" {
		if api.video, err = newVideoBridge(e.videoOut); err != nil {
			log.Fatal(err)
		}
	}
	if *opsAgent != "" {
		suggestions := []string{}
		for _, q := range strings.Split(*opsAgentSuggestions, "|") {
			if q = strings.TrimSpace(q); q != "" {
				suggestions = append(suggestions, q)
			}
		}
		api.opsAgent = map[string]any{"id": *opsAgent, "name": *opsAgentName, "alias": *opsAgentAlias,
			"intro": strings.ReplaceAll(*opsAgentIntro, "{name}", *opsAgentName), "suggestions": suggestions}
	}
	files, _ := fs.Sub(static, "static")
	mux := http.NewServeMux()
	mux.Handle("GET /", pageHeaders(http.FileServer(http.FS(files))))
	api.routes(mux)
	server := &http.Server{Addr: *listen, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	log.Printf("dispatcher on %s, engine %s %s", *listen, *path, base)
	log.Fatal(server.ListenAndServe())
}

func pageHeaders(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy", "default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:; frame-ancestors 'none'")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		h.ServeHTTP(w, r)
	})
}
