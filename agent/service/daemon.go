//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"syscall"
	"time"
)

// daemon owns the engine. The engine's control FIFO answers nothing, so each tool writes its
// command under cmdMu and then waits for what the command leads to: an engine event after
// it, a change in the status file, or a client_error event (the engine's report of a
// command it refused).
type daemon struct {
	cfg     *config
	eng     *engine
	journal *journal
	store   *store
	changed *notifier
	limit   *limiter
	audit   *auditLog
	cmdMu   sync.Mutex
	quit    chan struct{}
	once    sync.Once

	mu     sync.Mutex
	status map[string]any // the engine's last status document
	ready  bool           // the engine runs and is registered
	alias  string         // the alias state: "", activating, active, or why it is not

	speakMu   sync.Mutex // one speech at a time (voice.go)
	speechDir string     // where the engine writes received talk bursts
	video     *videoFeed // the pictures streamed to the engine (video.go)
}

func newDaemon(cfg *config) (*daemon, error) {
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cfg.OutboxDir, 0o700); err != nil {
		return nil, err
	}
	speechDir := filepath.Join(cfg.DataDir, "speech-in")
	for _, dir := range []string{speechDir, filepath.Join(cfg.DataDir, "speech"), filepath.Join(cfg.DataDir, "speech-out")} {
		if cfg.Voice.Enabled {
			if err := os.MkdirAll(dir, 0o700); err != nil {
				return nil, err
			}
		}
	}
	fifo := filepath.Join(cfg.DataDir, "video.fifo")
	engineConfig, err := runtimeConfig(cfg, speechDir, fifo)
	if err != nil {
		return nil, err
	}
	eng, err := newEngine(cfg.Engine.Path, engineConfig, cfg.Engine.Args)
	if err != nil {
		return nil, err
	}
	if eng.userID == "" && cfg.Alias != "" {
		return nil, errors.New("an alias needs MCData in the engine configuration (mcdata.user_id)")
	}
	changed := newNotifier()
	st, err := openStore(filepath.Join(cfg.DataDir, "store.json"), changed)
	if err != nil {
		return nil, err
	}
	st.speechKeep = cfg.Voice.Keep
	audit, err := openAudit(filepath.Join(cfg.DataDir, "audit.jsonl"))
	if err != nil {
		return nil, err
	}
	d := &daemon{cfg: cfg, eng: eng, journal: newJournal(changed), store: st, changed: changed,
		limit: &limiter{total: cfg.RatePerMin, perRecipient: cfg.RatePerRecipient}, audit: audit, quit: make(chan struct{}),
		speechDir: speechDir}
	if cfg.Video.Enabled {
		d.video = &videoFeed{fifo: fifo, frameBytes: cfg.Video.frameBytes(), frameRate: cfg.Video.FrameRate}
		if cfg.Video.Receive {
			if d.video.received, err = newVideoReceiver(fmt.Sprintf("127.0.0.1:%d", cfg.Video.ReceivePort)); err != nil {
				return nil, err
			}
		}
	}
	eng.onEvent = d.engineEvent
	eng.onExit = changed.fire
	return d, nil
}

// engineEvent takes an engine event into the journal; a received talk burst goes into the store, and with
// video.auto_transmit an MCVideo call asks for the permission to transmit.
func (d *daemon) engineEvent(fields map[string]any) {
	switch fields["event"] {
	case "speech_received":
		if d.cfg.Voice.Enabled {
			d.absorbSpeech(fields)
		}
	case "mcvideo_call_active":
		if d.cfg.Video.AutoTransmit {
			go d.autoTransmit()
		}
	}
	d.journal.append(fields)
}

func runDaemon(args []string) int {
	flags := flag.NewFlagSet("daemon", flag.ContinueOnError)
	file := flags.String("config", "/etc/mcx-agent/agent.json", "the agent's configuration")
	if flags.Parse(args) != nil {
		return 2
	}
	cfg, err := loadConfig(*file)
	if err != nil {
		log.Print(err)
		return 1
	}
	d, err := newDaemon(cfg)
	if err != nil {
		log.Print(err)
		return 1
	}
	listener, err := listenUnix(socketPath(cfg.Socket))
	if err != nil {
		log.Print(err)
		return 1
	}
	if cfg.MCPHTTP != nil && cfg.MCPHTTP.Listen != "" {
		token, err := readToken(cfg.MCPHTTP.TokenFile)
		if err != nil {
			log.Printf("mcp_http: %v", err)
			return 1
		}
		server := &http.Server{Addr: cfg.MCPHTTP.Listen, Handler: newMCPHTTP(token, cfg.MCPHTTP.AllowedOrigins, d.call, d.video),
			ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 2 * time.Minute}
		go func() { log.Printf("mcp_http: %v", server.ListenAndServe()) }()
		log.Printf("mcx-agent: MCP over HTTP on %s/mcp", cfg.MCPHTTP.Listen)
	}
	go d.poll()
	go d.supervise()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stop
		d.shutdown()
		_ = listener.Close()
	}()
	log.Printf("mcx-agent: %s as %s, socket %s", cfg.Engine.Config, d.identity(), listener.Addr())
	err = http.Serve(listener, d.handler())
	if errors.Is(err, net.ErrClosed) {
		return 0
	}
	log.Print(err)
	return 1
}

// listenUnix listens on a unix socket only its owner may use.
func listenUnix(file string) (net.Listener, error) {
	if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
		return nil, err
	}
	_ = os.Remove(file)
	old := syscall.Umask(0o177)
	listener, err := net.Listen("unix", file)
	syscall.Umask(old)
	return listener, err
}

// shutdown stops the engine for good.
func (d *daemon) shutdown() {
	d.once.Do(func() { close(d.quit) })
	d.eng.stop()
}

// poll reads the engine's status (written every 200 ms) into the daemon and the store.
func (d *daemon) poll() {
	tick := time.NewTicker(200 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-d.quit:
			return
		case <-tick.C:
		}
		st, err := d.eng.state()
		if err != nil {
			continue
		}
		d.mu.Lock()
		d.status = st
		d.mu.Unlock()
		if err := d.store.absorb(st); err != nil {
			log.Printf("store: %v", err)
		}
		d.changed.fire()
	}
}

func (d *daemon) latest() map[string]any {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.status
}

// identity is the agent's IDs for the log.
func (d *daemon) identity() string {
	ids := []string{}
	for _, id := range []string{d.eng.userID, d.eng.mcpttID, d.eng.mcvideoID} {
		if id != "" {
			ids = append(ids, id)
		}
	}
	return strings.Join(ids, ", ")
}

// supervise runs the engine for good: it starts it, waits for registration, activates the
// alias once MCData is authorised, and starts it again three seconds after it ends.
func (d *daemon) supervise() {
	for {
		select {
		case <-d.quit:
			return
		default:
		}
		d.setState(false, "")
		if err := d.eng.start(); err != nil {
			log.Printf("engine: %v", err)
			time.Sleep(3 * time.Second)
			continue
		}
		d.mu.Lock()
		d.status = nil
		d.mu.Unlock()
		_, done := d.eng.running()
		if d.waitReady(done) {
			d.setState(true, "")
			log.Print("engine registered")
			if d.cfg.Alias != "" {
				go d.keepAlias(done)
			}
		}
		<-done
		select {
		case <-d.quit:
			return
		case <-time.After(3 * time.Second):
		}
	}
}

// waitReady waits until the engine is registered; false when the engine ends first.
func (d *daemon) waitReady(done chan struct{}) bool {
	for {
		select {
		case <-done:
			return false
		default:
		}
		err := d.changed.waitUntil(time.Second, func() (bool, error) {
			st := d.latest()
			return st["registered"] == true, nil
		})
		if err == nil {
			return true
		}
	}
}

func (d *daemon) setState(ready bool, alias string) {
	d.mu.Lock()
	d.ready, d.alias = ready, alias
	d.mu.Unlock()
}

func (d *daemon) setAlias(state string) {
	d.mu.Lock()
	d.alias = state
	d.mu.Unlock()
}

// keepAlias holds the agent's alias while the engine runs: it activates it, and again
// alias_retry_s after it is lost (taken over, deactivated) or could not be activated.
func (d *daemon) keepAlias(done chan struct{}) {
	retry := time.Duration(max(d.cfg.AliasRetryS, 1)) * time.Second
	for {
		if d.serviceReady("mcdata") != nil {
			// Not authorised for MCData (yet): look again in a second.
			select {
			case <-done:
				return
			case <-d.quit:
				return
			case <-time.After(time.Second):
			}
			continue
		}
		if !d.aliasActive(d.latest()) {
			d.mu.Lock()
			lost := d.alias == "active"
			d.mu.Unlock()
			if lost {
				log.Printf("alias %s lost", d.cfg.Alias)
				d.setAlias("lost: taken over or deactivated")
			}
			d.activateAlias()
		}
		select {
		case <-done:
			return
		case <-d.quit:
			return
		case <-time.After(retry):
		}
	}
}

// activateAlias activates the agent's functional alias (TS 24.282 clause 22.2.1.2) and,
// once active, presents it on what the agent sends. Another user's alias is not taken over.
func (d *daemon) activateAlias() {
	alias := d.cfg.Alias
	d.setAlias("activating")
	outcome, err := d.transact("mcdata-fa activate "+alias, 30*time.Second,
		func(_ []engineEvent, st map[string]any) (any, bool, error) {
			if d.aliasActive(st) {
				return "activated", true, nil
			}
			last, _ := st["mcdata_fa_last_result"].(map[string]any)
			outcomes, _ := last["outcomes"].(map[string]any)
			if o := str(outcomes[alias]); o != "" && o != "activated" {
				return o, true, nil
			}
			return nil, false, nil
		})
	if err == nil && outcome != "activated" {
		err = fmt.Errorf("%v", outcome)
	}
	if err == nil {
		err = d.fire("mcdata-fa use " + alias)
	}
	if err != nil {
		log.Printf("alias %s: %v", alias, err)
		d.setAlias("not active: " + err.Error())
		return
	}
	log.Printf("alias %s active", alias)
	d.setAlias("active")
}

// aliasActive reports whether the engine holds the agent's alias active: another user may
// take it over at any time.
func (d *daemon) aliasActive(st map[string]any) bool {
	aliases, _ := st["mcdata_functional_aliases"].([]any)
	return d.cfg.Alias != "" && slices.Contains(aliases, any(d.cfg.Alias))
}

// sendingAlias is the alias the next message presents, or why the agent may not send.
func (d *daemon) sendingAlias() (string, error) {
	if d.cfg.Alias == "" {
		return "", nil
	}
	d.mu.Lock()
	state := d.alias
	d.mu.Unlock()
	if state == "active" && d.aliasActive(d.latest()) {
		return d.cfg.Alias, nil
	}
	if *d.cfg.RequireAlias {
		switch state {
		case "active":
			state = "no longer active (taken over or deactivated)"
		case "":
			state = "not activated yet"
		}
		return "", fail(codeAliasInactive, "functional alias %s is %s", d.cfg.Alias, state)
	}
	return "", nil
}

// readiness reports whether the engine runs and is registered.
func (d *daemon) readiness() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if !d.ready {
		reason := "starting"
		if running, _ := d.eng.running(); !running {
			reason = "the engine is not running"
		}
		if exit := d.eng.lastExit(); exit != "" {
			reason += ": " + exit
		}
		return fail(codeNotReady, "the engine is not ready (%s)", reason)
	}
	return nil
}

// services are the MC services a tool needs: the name in errors and the engine's status key of its authorisation.
var services = map[string]struct{ name, authorized string }{
	"mcdata":  {"MCData", "mcdata_authorized"},
	"mcptt":   {"MCPTT", "authorized"},
	"mcvideo": {"MCVideo", "mcvideo_authorized"},
}

// enabled reports whether the agent uses a service: MCData with mcdata.user_id, voice (MCPTT) and video (MCVideo)
// when the configuration turns them on.
func (d *daemon) enabled(service string) bool {
	switch service {
	case "mcdata":
		return d.eng.userID != ""
	case "mcptt":
		return d.cfg.Voice.Enabled
	case "mcvideo":
		return d.cfg.Video.Enabled
	}
	return false
}

// serviceReady reports whether the agent can use a service: enabled, the engine registered and authorised for it.
func (d *daemon) serviceReady(service string) error {
	s := services[service]
	if !d.enabled(service) {
		what := map[string]string{"mcdata": "MCData (no mcdata.user_id in the engine configuration)",
			"mcptt": "voice (voice.enabled)", "mcvideo": "video (video.enabled)"}[service]
		return fail(codeNotEnabled, "this agent does not use %s", what)
	}
	if err := d.readiness(); err != nil {
		return err
	}
	if d.latest()[s.authorized] != true {
		return fail(codeNotReady, "the %s service is not ready (not authorised yet)", s.name)
	}
	return nil
}

// transact writes one command and waits until check finds its outcome in the events that
// followed it or in the status. A client_error event after the command is its failure.
func (d *daemon) transact(line string, timeout time.Duration,
	check func(events []engineEvent, st map[string]any) (any, bool, error)) (any, error) {
	if err := d.readiness(); err != nil {
		return nil, err
	}
	d.cmdMu.Lock()
	defer d.cmdMu.Unlock()
	from := d.journal.last()
	if err := d.eng.command(line); err != nil {
		return nil, fail(codeNotReady, "%v", err)
	}
	var result any
	err := d.changed.waitUntil(timeout, func() (bool, error) {
		events := d.journal.since(from)
		for _, ev := range events {
			if ev.Kind == "client_error" {
				return false, fail(codeEngineRefused, "%s", str(ev.Fields["reason"]))
			}
		}
		if running, _ := d.eng.running(); !running {
			return false, fail(codeNotReady, "the engine ended: %s", d.eng.lastExit())
		}
		r, ok, err := check(events, d.latest())
		result = r
		return ok, err
	})
	if errors.Is(err, errTimeout) {
		return nil, fmt.Errorf("no outcome from the engine within %s: %w", timeout, errTimeout)
	}
	return result, err
}

// maxRequestBytes bounds a tool call's request: speech travels in it, base64 encoded.
const maxRequestBytes = 32 << 20

// fire writes a command that reports nothing when it succeeds: no client_error within half a
// second is success. The engine runs a command within one pass of its loop.
func (d *daemon) fire(line string) error {
	_, err := d.transact(line, 500*time.Millisecond,
		func([]engineEvent, map[string]any) (any, bool, error) { return nil, false, nil })
	if errors.Is(err, errTimeout) {
		return nil
	}
	return err
}

// handler serves the tools: POST /call {"tool": ..., "args": {...}} answers
// {"ok": true, "result": ...} or {"ok": false, "error": "..."}.
func (d *daemon) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /call", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Tool string         `json:"tool"`
			Args map[string]any `json:"args"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxRequestBytes)).Decode(&req); err != nil {
			writeReply(w, nil, fail(codeInvalidArgument, "bad request: %v", err))
			return
		}
		result, err := d.call(req.Tool, req.Args)
		writeReply(w, result, err)
	})
	mux.HandleFunc("POST /video", d.video.serve)
	mux.HandleFunc("GET /video/received", d.video.serveReceived)
	return mux
}

func writeReply(w http.ResponseWriter, result any, err error) {
	w.Header().Set("Content-Type", "application/json")
	reply := map[string]any{"ok": err == nil}
	if err != nil {
		reply["error"], reply["code"] = err.Error(), errorCode(err)
	} else {
		reply["result"] = result
	}
	_ = json.NewEncoder(w).Encode(reply)
}

// call checks the arguments against the tool's parameters and runs it; the tools that act
// on the network go in the audit log.
func (d *daemon) call(name string, raw map[string]any) (any, error) {
	t := findTool(name)
	if t == nil {
		return nil, fail(codeUnknownTool, "unknown tool %q", name)
	}
	a, err := t.bind(raw)
	if err != nil {
		return nil, err
	}
	if t.service != "" {
		if err := d.serviceReady(t.service); err != nil {
			return nil, err
		}
	}
	result, err := t.run(d, a)
	if t.acts {
		d.audit.record(name, raw, result, err)
	}
	return result, err
}
