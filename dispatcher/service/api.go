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
	mux.HandleFunc("POST /api/msgstore/upload", a.guard(a.storeFile))
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
		"private_emergency_state", "functional_alias", "requested_functional_aliases", "active_functional_aliases",
		"takeover_possible_functional_aliases", "last_fa_result", "functional_alias_subscription_result",
		"calling_functional_alias", "called_functional_alias", "functional_alias_redirect", "regroups",
		"regroup_result", "xcap_last", "msgstore", "profile_groups",
		"mcdata_authorized", "mcdata_affiliated", "mcdata_inbox",
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

var documentName = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// Message store flags (TS 24.282 clause 21.2.3.1, OMA NMS system flags such as \Seen and \Flagged, or keywords) and
// search criteria (name=value attributes, flag=<flag>).
var (
	storeFlag      = regexp.MustCompile(`^\\?[A-Za-z][A-Za-z0-9$_-]{0,31}$`)
	storeCriterion = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9-]{0,31}=\\?[A-Za-z0-9._:@+/-]{1,256}$`)
)

// msgstoreLine turns a message store action into one "mcdata-msgstore" engine command (TS 24.282 clause 21.2).
func msgstoreLine(req map[string]any, str func(string) string, id func(string) (string, error)) (string, error) {
	op := str("op")
	switch op {
	case "sync", "folders", "subscribe", "unsubscribe":
		// Synchronisation (clause 21.2.17.1), the folder hierarchy (clause 21.2.23.1), the notification channel and
		// the store's subscription (clauses 21.2.19.1, 21.2.12A.1 and 21.2.22.1).
		return "mcdata-msgstore " + op, nil
	case "get", "payload", "retrieve", "folder", "folder-delete":
		// An object (clause 21.2.1.1), its payload, its retrieval (clause 21.2.24.1); a folder's objects (clause
		// 21.2.18.1) or its deletion (clause 21.2.7.1).
		target, err := id("id")
		if err != nil {
			return "", err
		}
		return "mcdata-msgstore " + op + " " + target, nil
	case "delete":
		// One object, or several in bulk (clause 21.2.4.1).
		targets, err := ids(req["ids"], 1, 20)
		if err != nil {
			return "", err
		}
		return "mcdata-msgstore delete " + strings.Join(targets, " "), nil
	case "flag", "unflag", "bulk-flag":
		// Flags set or removed (clause 21.2.3.1).
		flag := str("flag")
		if !storeFlag.MatchString(flag) {
			return "", fmt.Errorf("%q is not a message store flag", flag)
		}
		targets, err := ids(req["ids"], 1, 20)
		if err != nil {
			return "", err
		}
		if op == "bulk-flag" {
			return "mcdata-msgstore bulk-flag " + flag + " " + strings.Join(targets, " "), nil
		}
		if len(targets) != 1 {
			return "", errors.New("one object at a time; bulk-flag sets a flag on several")
		}
		return "mcdata-msgstore " + op + " " + targets[0] + " " + flag, nil
	case "search":
		// Objects by attribute and flag (clause 21.2.2.1).
		list, _ := req["criteria"].([]any)
		var criteria []string
		for _, item := range list {
			c, _ := item.(string)
			if !storeCriterion.MatchString(c) {
				return "", fmt.Errorf("%q is not a name=value or flag=<flag> criterion", c)
			}
			criteria = append(criteria, c)
		}
		if len(criteria) == 0 || len(criteria) > 5 {
			return "", errors.New("between 1 and 5 criteria are needed")
		}
		return "mcdata-msgstore search " + strings.Join(criteria, " "), nil
	case "folder-search", "folder-create":
		// Folders by name (clause 21.2.11.1); a new folder, optionally in a parent (clause 21.2.8.1).
		name := str("name")
		if !documentName.MatchString(name) {
			return "", fmt.Errorf("%q is not a folder name", name)
		}
		line := "mcdata-msgstore " + op + " " + name
		if op == "folder-create" && str("parent") != "" {
			parent, err := id("parent")
			if err != nil {
				return "", err
			}
			line += " " + parent
		}
		return line, nil
	case "move", "copy", "move-folder", "copy-folder":
		// Objects or folders moved or copied into a folder (clauses 21.2.6.1 and 21.2.10.1).
		folder, err := id("folder")
		if err != nil {
			return "", err
		}
		targets, err := ids(req["ids"], 1, 20)
		if err != nil {
			return "", err
		}
		return "mcdata-msgstore " + op + " " + folder + " " + strings.Join(targets, " "), nil
	case "restart":
		// The notification stream restarted from an index (clause 21.2.14A.1).
		index, ok := req["index"].(float64)
		if !ok || index < 0 || index != float64(int64(index)) {
			return "", errors.New("index must be a whole number, 0 or more")
		}
		return fmt.Sprintf("mcdata-msgstore restart %d", int64(index)), nil
	default:
		return "", fmt.Errorf("unknown message store operation %q", op)
	}
}

// temporaryDocument names the group management document of a temporary group after the user part of its ID.
func temporaryDocument(tgi string) string {
	name := tgi
	if i := strings.Index(name, ":"); i >= 0 {
		name = name[i+1:]
	}
	if i := strings.Index(name, "@"); i >= 0 {
		name = name[:i]
	}
	if !documentName.MatchString(name) {
		return ""
	}
	return name
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
		// TS 24.379 clause 11.1.1.2.1.1 step 14c: a private call to a functional alias, resolved by the server to a
		// user who activated it; the engine has no emergency call to an alias.
		if flag("to_alias") {
			if flag("emergency") {
				return "", errors.New("no emergency private call to a functional alias")
			}
			line = "private-fa " + target
		}
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
		// takes it (optionally an emergency one, clause 6.2.8.3.2); or to one functional alias, every user who
		// activated it invited.
		if str("alias") != "" {
			alias, err := id("alias")
			if err != nil {
				return "", err
			}
			if flag("emergency") {
				return "", errors.New("no emergency first-to-answer call to a functional alias")
			}
			return "first-to-answer-fa " + alias, nil
		}
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
	case "fa":
		// TS 24.379 clause 9A.2.1.2: the functional alias status changed: activated, taken over from its holder
		// (offered as take-over-possible, clause 9A.2.1.3) or deactivated; the engine first subscribes to the
		// user's functional alias status (clause 9A.2.1.1).
		op := str("op")
		if op != "activate" && op != "takeover" && op != "deactivate" {
			return "", fmt.Errorf("unknown functional alias operation %q", op)
		}
		aliases, err := ids(req["aliases"], 1, 10)
		if err != nil {
			return "", err
		}
		return "fa " + op + " " + strings.Join(aliases, " "), nil
	case "fa-use":
		// The functional alias the next calls present (TS 24.379 clause 10.1.1.2.1.1, clause 11.1.1.2.1.1:
		// <functional-alias-URI>); "" stops.
		if str("alias") == "" {
			return "fa use none", nil
		}
		alias, err := id("alias")
		if err != nil {
			return "", err
		}
		return "fa use " + alias, nil
	case "regroup":
		// TS 24.379 clause 16.2.1.1 (groups), clause 16.3.1.1 (users) and clause 16.2.1.2 (removal): a regroup using
		// a preconfigured group, whose configuration it takes, asked of the participating function.
		op := str("op")
		if op != "create" && op != "users" && op != "remove" {
			return "", fmt.Errorf("unknown regroup operation %q", op)
		}
		tgi, err := id("tgi")
		if err != nil {
			return "", err
		}
		if op == "remove" {
			return "regroup remove " + tgi, nil
		}
		preconfigured, err := id("preconfigured")
		if err != nil {
			return "", err
		}
		members, err := ids(req["members"], 1, 20)
		if err != nil {
			return "", err
		}
		return "regroup " + op + " " + tgi + " " + preconfigured + " " + strings.Join(members, ","), nil
	case "temporary":
		// TS 24.481 clause 6.3.14.2 (formation) and clause 6.3.15.2 (tear down): a temporary group of groups, a
		// document of the dispatcher's own tree at the group management server, named after the group's ID.
		tgi, err := id("tgi")
		if err != nil {
			return "", err
		}
		document := temporaryDocument(tgi)
		if document == "" {
			return "", fmt.Errorf("%q has no name for a document", tgi)
		}
		switch op := str("op"); op {
		case "delete":
			return "temporary-delete " + document, nil
		case "create":
			groups, err := ids(req["groups"], 1, 20)
			if err != nil {
				return "", err
			}
			line := "temporary-create " + document + " " + tgi + " " + strings.Join(groups, ",")
			if flag("chat") {
				line += " chat"
			}
			if flag("protected") {
				line += " protected"
			}
			return line, nil
		default:
			return "", fmt.Errorf("unknown temporary group operation %q", op)
		}
	case "msgstore":
		return msgstoreLine(req, str, id)
	case "msgstore-ctrl":
		// TS 24.282 clauses 21.3.2.2 and 21.3.2.3: the storage of the user's communications in the message store,
		// enabled or disabled for all private, all group, or the users and groups named.
		op := str("op")
		if op != "enable" && op != "disable" {
			return "", fmt.Errorf("unknown storage control %q", op)
		}
		line := "mcdata-msgstore-ctrl " + op
		if flag("all_private") {
			line += " all-private"
		}
		if flag("all_group") {
			line += " all-group"
		}
		for _, key := range []string{"private", "group"} {
			if list, _ := req[key].([]any); len(list) > 0 {
				named, err := ids(list, 1, 20)
				if err != nil {
					return "", err
				}
				line += " " + key + "=" + strings.Join(named, ",")
			}
		}
		if line == "mcdata-msgstore-ctrl "+op {
			return "", errors.New("name all private, all group, users or groups")
		}
		return line, nil
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
	path, ok := a.saveUpload(w, r, "fd-")
	if !ok {
		return
	}
	target := r.FormValue("target")
	if !word.MatchString(target) {
		_ = os.RemoveAll(filepath.Dir(path))
		fail(w, http.StatusBadRequest, errors.New("target is missing or not a single word"))
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

// storeFile stores a file from the browser in the message store as a new object, optionally in a folder (TS 24.282
// clause 21.2.15.1).
func (a *api) storeFile(w http.ResponseWriter, r *http.Request) {
	if !fromUI(w, r) {
		return
	}
	path, ok := a.saveUpload(w, r, "store-")
	if !ok {
		return
	}
	line := "mcdata-msgstore upload " + path
	if folder := r.FormValue("folder"); folder != "" {
		if !word.MatchString(folder) {
			_ = os.RemoveAll(filepath.Dir(path))
			fail(w, http.StatusBadRequest, errors.New("folder is not a single word"))
			return
		}
		line += " " + folder
	}
	if err := a.engine.command(line); err != nil {
		fail(w, http.StatusServiceUnavailable, err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"sent": line})
}

// saveUpload saves the "file" field of a form from the browser in a directory of its own in the outbox, for the
// engine to read; on failure it answers the request.
func (a *api) saveUpload(w http.ResponseWriter, r *http.Request, prefix string) (string, bool) {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<20)
	file, header, err := r.FormFile("file")
	if err != nil {
		fail(w, http.StatusBadRequest, err)
		return "", false
	}
	defer file.Close()
	name := strings.Trim(unsafeName.ReplaceAllString(filepath.Base(header.Filename), "_"), "._")
	if name == "" {
		name = "file"
	}
	dir, err := os.MkdirTemp(a.outbox, prefix)
	if err != nil {
		fail(w, http.StatusInternalServerError, err)
		return "", false
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
		return "", false
	}
	return path, true
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
