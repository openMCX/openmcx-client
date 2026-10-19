//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"time"

	"golang.org/x/net/websocket"
)

type api struct {
	engine *engine
	audio  *audioBridge
	outbox string
	pos    *position // nil in lab mode
}

func (a *api) routes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/session", a.session)
	mux.HandleFunc("POST /api/login", a.login)
	mux.HandleFunc("GET /api/login/browser", a.browserLogin)
	mux.HandleFunc("GET "+callbackPath, a.browserCallback)
	mux.HandleFunc("POST /api/activate", a.activate)
	mux.HandleFunc("POST /api/logout", a.logout)
	mux.HandleFunc("GET /api/events", a.events)
	mux.HandleFunc("GET /api/state", a.guard(a.getState))
	mux.HandleFunc("POST /api/command", a.guard(a.command))
	mux.HandleFunc("POST /api/fd", a.guard(a.sendFile))
	mux.HandleFunc("GET /api/files/{id}", a.guard(a.receivedFile))
	mux.Handle("GET /api/audio", websocket.Server{Handshake: a.audioHandshake, Handler: a.audioSocket})
}

// guard admits a request in lab mode, or with the signed-in operator's session cookie.
func (a *api) guard(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.allowed(r) {
			fail(w, http.StatusUnauthorized, errors.New("sign in first"))
			return
		}
		h(w, r)
	}
}

func (a *api) allowed(r *http.Request) bool { return a.pos == nil || a.pos.signedIn(r) }

// audioHandshake refuses cross-site use (the audio socket needs this page's origin) and,
// in position mode, a browser without the operator's session. POSTs need the X-Dispatcher
// header, which a form on another site cannot send.
func (a *api) audioHandshake(cfg *websocket.Config, r *http.Request) error {
	origin, err := url.Parse(r.Header.Get("Origin"))
	if err != nil || origin.Host != r.Host {
		return errors.New("cross-origin audio refused")
	}
	if !a.allowed(r) {
		return errors.New("sign in first")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

func (a *api) sessionInfo(r *http.Request) map[string]any {
	if a.pos == nil {
		return map[string]any{"mode": "lab", "signed_in": true}
	}
	return a.pos.info(r)
}

func (a *api) session(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, a.sessionInfo(r))
}

// credentials reads a sign-in or activation request (position mode only).
func (a *api) credentials(w http.ResponseWriter, r *http.Request) (map[string]string, bool) {
	if !fromUI(w, r) {
		return nil, false
	}
	if a.pos == nil {
		fail(w, http.StatusNotFound, errors.New("this dispatcher runs in lab mode, with a configured identity"))
		return nil, false
	}
	var req map[string]string
	if err := json.NewDecoder(io.LimitReader(r.Body, 8192)).Decode(&req); err != nil {
		fail(w, http.StatusBadRequest, err)
		return nil, false
	}
	return req, true
}

func (a *api) login(w http.ResponseWriter, r *http.Request) {
	req, ok := a.credentials(w, r)
	if !ok {
		return
	}
	if a.pos.browser {
		fail(w, http.StatusNotFound, errors.New("this dispatcher signs in on IdMS's own page: use Sign in with IdMS"))
		return
	}
	if req["username"] == "" || req["password"] == "" {
		fail(w, http.StatusBadRequest, errors.New("give the login and the password"))
		return
	}
	if status, err := a.pos.signIn(w, req["username"], req["password"]); err != nil {
		fail(w, status, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"signed_in": true, "operator": req["username"]})
}

func (a *api) activate(w http.ResponseWriter, r *http.Request) {
	req, ok := a.credentials(w, r)
	if !ok {
		return
	}
	if a.pos.browser {
		fail(w, http.StatusNotFound, errors.New("this dispatcher has a configured identity: nothing to activate"))
		return
	}
	if req["url"] == "" || req["password"] == "" {
		fail(w, http.StatusBadRequest, errors.New("give the activation link and the account's password"))
		return
	}
	if status, err := a.pos.activate(w, req["url"], req["password"]); err != nil {
		fail(w, status, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"signed_in": true})
}

func (a *api) logout(w http.ResponseWriter, r *http.Request) {
	if !fromUI(w, r) {
		return
	}
	if a.pos != nil {
		a.pos.signOut(w, r)
	}
	writeJSON(w, http.StatusOK, map[string]any{"signed_in": false})
}

// view is what the UI needs from the engine's status and configuration, with the session.
// Without a session (position mode) it is the session alone.
func (a *api) view(r *http.Request) (map[string]any, error) {
	session := a.sessionInfo(r)
	if session["signed_in"] != true {
		return map[string]any{"session": session}, nil
	}
	s, err := a.engine.state()
	if err != nil {
		return nil, err
	}
	pick := func(keys ...string) map[string]any {
		out := map[string]any{}
		for _, k := range keys {
			if v, ok := s[k]; ok {
				out[k] = v
			}
		}
		return out
	}
	v := pick("registered", "authorized", "affiliated", "call_state", "call_type", "private_peer", "floor_state",
		"floor_owner", "talking", "floor_control", "priority", "priority_pending", "alerts", "alert_locations",
		"last_call_failure", "queue_position", "callback_requests_sent", "callback_requests_received",
		"first_to_answer", "ambient", "last_ambient", "announced_transfer", "last_forwarding", "service_outcomes",
		"conference_participants", "mcdata_alert_state", "mcdata_alerts_received", "mcdata_alert_last_result",
		"private_emergency_state", "profile_groups", "mcdata_authorized", "mcdata_affiliated", "mcdata_inbox",
		"mcdata_sent", "mcdata_last_result", "mcdata_files", "mcdata_files_sent", "mcdata_fd_last_result")
	v["session"] = session
	if raw, err := os.ReadFile(a.engine.configPath()); err == nil {
		type service struct {
			UserID  string `json:"user_id"`
			GroupID string `json:"group_id"`
		}
		var cfg struct {
			MCPTT  service `json:"mcptt"`
			MCData service `json:"mcdata"`
		}
		if json.Unmarshal(raw, &cfg) == nil {
			group := cfg.MCPTT.GroupID
			if g, ok := s["group_id"].(string); ok {
				group = g // the group selected now
			}
			v["identity"] = map[string]string{"user": cfg.MCPTT.UserID, "group": group,
				"mcdata_user": cfg.MCData.UserID, "mcdata_group": cfg.MCData.GroupID}
		}
	}
	return v, nil
}

func (a *api) getState(w http.ResponseWriter, r *http.Request) {
	v, err := a.view(r)
	if err != nil {
		fail(w, http.StatusServiceUnavailable, err)
		return
	}
	writeJSON(w, http.StatusOK, v)
}

// events streams the view whenever it changes; a browser without a session gets the
// session state only, and sees a sign-out as it happens.
func (a *api) events(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		fail(w, http.StatusInternalServerError, errors.New("streaming unsupported"))
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	var last []byte
	tick := time.NewTicker(250 * time.Millisecond)
	defer tick.Stop()
	for {
		if v, err := a.view(r); err == nil {
			if raw, _ := json.Marshal(v); !bytes.Equal(raw, last) {
				last = raw
				fmt.Fprintf(w, "data: %s\n\n", raw)
				flusher.Flush()
			}
		}
		select {
		case <-r.Context().Done():
			return
		case <-tick.C:
		}
	}
}

// Identities and IDs go into engine command lines, so they are single words.
var word = regexp.MustCompile(`^[A-Za-z0-9._:@+=/-]{1,256}$`)

// commandLine turns a UI action into one engine command. Only these actions exist.
// ids reads a list of between min and max single words, each once.
func ids(value any, min, max int) ([]string, error) {
	list, _ := value.([]any)
	var out []string
	for _, item := range list {
		s, _ := item.(string)
		if !word.MatchString(s) {
			return nil, fmt.Errorf("%q is not a single word", s)
		}
		if !slices.Contains(out, s) {
			out = append(out, s)
		}
	}
	if len(out) < min || len(out) > max {
		return nil, fmt.Errorf("between %d and %d distinct IDs are needed", min, max)
	}
	return out, nil
}

func commandLine(req map[string]any) (string, error) {
	str := func(key string) string { s, _ := req[key].(string); return s }
	flag := func(key string) bool { b, _ := req[key].(bool); return b }
	id := func(key string) (string, error) {
		if s := str(key); word.MatchString(s) {
			return s, nil
		}
		return "", fmt.Errorf("%s is missing or not a single word", key)
	}
	switch action := str("action"); action {
	case "call":
		// TS 24.379 clause 10.1.2.2.1.1: a chat group call is joined (and started) by the same request, on the selected
		// chat group; it has no broadcast form.
		verb := "call"
		if flag("chat") {
			verb = "chat"
		}
		switch kind := str("kind"); kind {
		case "", "normal":
			return verb, nil
		case "emergency", "imminent":
			return verb + " " + kind, nil
		case "broadcast":
			if verb == "chat" {
				return "", errors.New("a chat group call has no broadcast form")
			}
			return "call broadcast", nil
		default:
			return "", fmt.Errorf("unknown call kind %q", kind)
		}
	case "group":
		// The engine allows only a group of its configuration or of the user profile.
		target, err := id("id")
		if err != nil {
			return "", err
		}
		return "group " + target, nil
	case "private":
		target, err := id("target")
		if err != nil {
			return "", err
		}
		line := "private " + target
		if flag("manual") {
			line += " manual"
		}
		if flag("emergency") {
			line += " emergency"
		}
		// TS 24.379 clause 11.1.2.2: a private call without floor control.
		if flag("no_floor") {
			line += " no-floor"
		}
		return line, nil
	case "priority":
		// TS 24.379 clauses 10.1.1.2.1.3 to 10.1.1.2.1.5 and 11.1.1.2.1.4: the call in progress raised to, or brought
		// back from, emergency or imminent peril.
		level := str("level")
		if level != "emergency" && level != "imminent" {
			return "", fmt.Errorf("unknown priority level %q", level)
		}
		if flag("on") {
			return level + " on", nil
		}
		return level + " off", nil
	case "callback", "callback-cancel", "callback-fulfil":
		// TS 24.379 clause 11.1.5: a private call call-back requested from a user or cancelled, or one received
		// fulfilled by calling the requester back.
		target, err := id("target")
		if err != nil {
			return "", err
		}
		if action != "callback" {
			return action + " " + target, nil
		}
		switch urgency := str("urgency"); urgency {
		case "":
			return "callback " + target, nil
		case "low", "normal", "high": // <urgency-ind>, TS 24.379 annex F.1
			return "callback " + target + " " + urgency, nil
		default:
			return "", fmt.Errorf("unknown urgency %q", urgency)
		}
	case "first-to-answer":
		// TS 24.379 clause 11.1.1.2.1.1 steps 10 and 15: a private call to two or more users, the first to answer
		// takes it (optionally an emergency one, clause 6.2.8.3.2).
		targets, err := ids(req["targets"], 2, 10)
		if err != nil {
			return "", err
		}
		line := "first-to-answer " + strings.Join(targets, " ")
		if flag("emergency") {
			line += " emergency"
		}
		return line, nil
	case "ambient":
		// TS 24.379 clause 11.1.6.2.1.1: an ambient listening call, remotely initiated (the dispatcher listens to the
		// target) or locally initiated (the target listens to the dispatcher).
		kind := str("kind")
		if kind != "remote" && kind != "local" {
			return "", fmt.Errorf("unknown ambient listening kind %q", kind)
		}
		target, err := id("target")
		if err != nil {
			return "", err
		}
		return "ambient " + kind + " " + target, nil
	case "remote-call", "remote-group-call":
		// TS 24.379 clauses 11.1.7.2.1 and 10.1.5.2.1: a user asked to place a private call to the target, or a group
		// call on the group; "silent": the user is not told (<notify-remote-user> false).
		remote, err := id("remote")
		if err != nil {
			return "", err
		}
		target, err := id("target")
		if err != nil {
			return "", err
		}
		line := action + " " + remote + " " + target
		if action == "remote-call" {
			switch mode := str("mode"); mode {
			case "":
			case "auto", "manual", "force-auto": // <ric-commencement-mode>
				line += " " + mode
			default:
				return "", fmt.Errorf("unknown commencement mode %q", mode)
			}
		}
		if flag("silent") {
			line += " silent"
		}
		return line, nil
	case "transfer":
		// TS 24.379 clause 11.1.8.2.1 (unannounced) and 11.1.8.2.3 (announced: hold and call the target, then
		// complete or cancel): the private call in progress transferred to the target.
		target, err := id("target")
		if err != nil {
			return "", err
		}
		switch step := str("step"); step {
		case "":
			return "transfer " + target, nil
		case "announced", "complete", "cancel":
			return "transfer " + target + " " + step, nil
		default:
			return "", fmt.Errorf("unknown transfer step %q", step)
		}
	case "forward":
		// TS 24.379 clause 11.1.9.2.1: the ringing private call forwarded to the target (manual input).
		target, err := id("target")
		if err != nil {
			return "", err
		}
		return "forward " + target, nil
	case "mcdata-alert":
		// TS 24.282 clauses 16.2.1.1 and 16.2.1.2: the MCData emergency alert on the selected group, sent or
		// cancelled; "user" and "group": another user's alert cancelled, by an authorised user.
		if flag("on") {
			return "mcdata-alert", nil
		}
		if str("user") == "" {
			return "mcdata-alert cancel", nil
		}
		user, err := id("user")
		if err != nil {
			return "", err
		}
		group, err := id("group")
		if err != nil {
			return "", err
		}
		return "mcdata-alert cancel group=" + group + " " + user, nil
	case "answer", "reject", "hangup", "release":
		return action, nil
	case "ptt":
		return "ptt live", nil
	case "alert":
		if flag("on") {
			return "alert on", nil
		}
		// TS 24.379 clause 12.1.1.2: another user's alert cancelled, by an authorised user.
		if str("user") != "" {
			user, err := id("user")
			if err != nil {
				return "", err
			}
			return "alert off " + user, nil
		}
		return "alert off", nil
	case "sds":
		target, err := id("target")
		if err != nil {
			return "", err
		}
		text := strings.TrimSpace(str("text"))
		if text == "" || len(text) > 1000 || strings.ContainsAny(text, "\r\n") {
			return "", errors.New("the text must be one line of 1 to 1000 characters")
		}
		if flag("group") {
			return "sds-group " + target + " " + text, nil
		}
		return "sds " + target + " " + text, nil
	case "fd-accept", "fd-reject", "fd-defer":
		target, err := id("id")
		if err != nil {
			return "", err
		}
		return action + " " + target, nil
	default:
		return "", fmt.Errorf("unknown action %q", action)
	}
}

func fromUI(w http.ResponseWriter, r *http.Request) bool {
	if r.Header.Get("X-Dispatcher") != "1" {
		fail(w, http.StatusForbidden, errors.New("missing X-Dispatcher header"))
		return false
	}
	return true
}

func (a *api) command(w http.ResponseWriter, r *http.Request) {
	if !fromUI(w, r) {
		return
	}
	var req map[string]any
	if err := json.NewDecoder(io.LimitReader(r.Body, 8192)).Decode(&req); err != nil {
		fail(w, http.StatusBadRequest, err)
		return
	}
	line, err := commandLine(req)
	if err != nil {
		fail(w, http.StatusBadRequest, err)
		return
	}
	if err := a.engine.command(line); err != nil {
		fail(w, http.StatusServiceUnavailable, err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"sent": line})
}

var unsafeName = regexp.MustCompile(`[^A-Za-z0-9._-]+`)

// sendFile keeps an uploaded file in the outbox and sends it by file distribution.
func (a *api) sendFile(w http.ResponseWriter, r *http.Request) {
	if !fromUI(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<20)
	file, header, err := r.FormFile("file")
	if err != nil {
		fail(w, http.StatusBadRequest, err)
		return
	}
	defer file.Close()
	target := r.FormValue("target")
	if !word.MatchString(target) {
		fail(w, http.StatusBadRequest, errors.New("target is missing or not a single word"))
		return
	}
	name := strings.Trim(unsafeName.ReplaceAllString(filepath.Base(header.Filename), "_"), "._")
	if name == "" {
		name = "file"
	}
	dir, err := os.MkdirTemp(a.outbox, "fd-")
	if err != nil {
		fail(w, http.StatusInternalServerError, err)
		return
	}
	path := filepath.Join(dir, name)
	out, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err == nil {
		_, err = io.Copy(out, file)
		if cerr := out.Close(); err == nil {
			err = cerr
		}
	}
	if err != nil {
		fail(w, http.StatusBadRequest, err)
		return
	}
	line := "fd-send "
	if r.FormValue("mandatory") == "true" {
		line += "mandatory "
	}
	if r.FormValue("group") == "true" {
		line += "group="
	}
	line += target + " " + path
	if err := a.engine.command(line); err != nil {
		fail(w, http.StatusServiceUnavailable, err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"sent": line})
}

// receivedFile serves a file the engine downloaded, found by its message ID.
func (a *api) receivedFile(w http.ResponseWriter, r *http.Request) {
	s, err := a.engine.state()
	if err != nil {
		fail(w, http.StatusServiceUnavailable, err)
		return
	}
	files, _ := s["mcdata_files"].([]any)
	downloads := filepath.Join(filepath.Dir(a.engine.status), "downloads")
	for _, f := range files {
		file, _ := f.(map[string]any)
		if file["message_id"] != r.PathValue("id") {
			continue
		}
		path, _ := file["path"].(string)
		if path == "" || filepath.Dir(path) != downloads {
			break
		}
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filepath.Base(path)))
		w.Header().Set("Content-Type", "application/octet-stream")
		http.ServeFile(w, r, path)
		return
	}
	fail(w, http.StatusNotFound, errors.New("no downloaded file with that message ID"))
}

// audioSocket carries binary PCM frames both ways between one browser and the engine,
// until the browser leaves or its session ends.
func (a *api) audioSocket(ws *websocket.Conn) {
	ws.PayloadType = websocket.BinaryFrame
	out := a.audio.subscribe()
	defer a.audio.unsubscribe(out)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			var frame []byte
			if err := websocket.Message.Receive(ws, &frame); err != nil {
				return
			}
			if len(frame) > 0 && len(frame) <= 4096 && len(frame)%2 == 0 && a.allowed(ws.Request()) {
				a.audio.send(frame)
			}
		}
	}()
	check := time.NewTicker(time.Second)
	defer check.Stop()
	for {
		select {
		case <-done:
			return
		case <-check.C:
			if !a.allowed(ws.Request()) {
				ws.Close()
				return
			}
		case frame := <-out:
			if err := websocket.Message.Send(ws, frame); err != nil {
				return
			}
		}
	}
}
