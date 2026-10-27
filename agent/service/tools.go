//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"
)

// param is one argument of a tool; the CLI's flags and the MCP input schema come from it.
type param struct {
	name, kind, description string // kind: string, integer or boolean
	required                bool
	enum                    []string
	def                     any
}

type tool struct {
	name, description string
	params            []param
	acts              bool // it acts on the network: audited
	run               func(d *daemon, a args) (any, error)
}

// args are a tool's arguments after bind: every parameter with a default is present.
type args map[string]any

func (a args) s(name string) string { v, _ := a[name].(string); return v }
func (a args) b(name string) bool   { v, _ := a[name].(bool); return v }
func (a args) i(name string) int    { v, _ := a[name].(int); return v }
func (a args) has(name string) bool { _, ok := a[name]; return ok }

// bind checks raw arguments against the parameters: names, types, enumerations, required
// ones; it adds the defaults.
func (t *tool) bind(raw map[string]any) (args, error) {
	a := args{}
	for name := range raw {
		if !slices.ContainsFunc(t.params, func(p param) bool { return p.name == name }) {
			return nil, fail(codeInvalidArgument, "%s has no argument %q", t.name, name)
		}
	}
	for _, p := range t.params {
		v, ok := raw[p.name]
		if !ok || v == nil {
			if p.required {
				return nil, fail(codeInvalidArgument, "%s needs %q", t.name, p.name)
			}
			if p.def != nil {
				a[p.name] = p.def
			}
			continue
		}
		switch p.kind {
		case "string":
			s, ok := v.(string)
			if !ok {
				return nil, fail(codeInvalidArgument, "%q is a string", p.name)
			}
			if p.enum != nil && !slices.Contains(p.enum, s) {
				return nil, fail(codeInvalidArgument, "%q is one of %v", p.name, p.enum)
			}
			a[p.name] = s
		case "integer":
			f, ok := v.(float64)
			if !ok || f != float64(int(f)) {
				return nil, fail(codeInvalidArgument, "%q is an integer", p.name)
			}
			a[p.name] = int(f)
		case "boolean":
			b, ok := v.(bool)
			if !ok {
				return nil, fail(codeInvalidArgument, "%q is true or false", p.name)
			}
			a[p.name] = b
		}
	}
	return a, nil
}

// schema is the tool's JSON Schema for MCP.
func (t *tool) schema() map[string]any {
	props := map[string]any{}
	required := []string{}
	for _, p := range t.params {
		prop := map[string]any{"type": p.kind, "description": p.description}
		if p.enum != nil {
			prop["enum"] = p.enum
		}
		if p.def != nil {
			prop["default"] = p.def
		}
		props[p.name] = prop
		if p.required {
			required = append(required, p.name)
		}
	}
	return map[string]any{"type": "object", "properties": props, "required": required, "additionalProperties": false}
}

func findTool(name string) *tool {
	for i := range tools {
		if tools[i].name == name {
			return &tools[i]
		}
	}
	return nil
}

var timeoutParam = func(def int) param {
	return param{name: "timeout_s", kind: "integer", def: def,
		description: fmt.Sprintf("how long to wait, in seconds (1 to 300, default %d)", def)}
}

var tools = []tool{
	{
		name:        "status",
		description: "The agent's MCData state: whether it can send, its MCData ID, its functional alias, unread messages.",
		run:         (*daemon).toolStatus,
	},
	{
		name: "send_message",
		description: "Send a one-to-one MCData short data message (SDS) to an MCData user, as the agent's functional " +
			"alias. Returns the message ID and, depending on 'wait', the network's answer and the recipient's reports.",
		params: []param{
			{name: "to", kind: "string", required: true, description: "the recipient's MCData ID, a SIP URI (sip:user@domain)"},
			{name: "text", kind: "string", required: true, description: "the message; line breaks are kept"},
			{name: "report", kind: "string", enum: []string{"none", "delivered", "read", "both"}, def: "both",
				description: "which disposition notifications to ask the recipient for"},
			{name: "wait", kind: "string", enum: []string{"none", "sent", "delivered", "read"}, def: "sent",
				description: "return once the message is sent (the network accepted it), delivered or read; none returns at once"},
			timeoutParam(30),
		},
		acts: true,
		run:  (*daemon).toolSendMessage,
	},
	{
		name:        "reply_message",
		description: "Reply to a received message, in its conversation (InReplyTo), as the agent's functional alias.",
		params: []param{
			{name: "message_id", kind: "string", required: true, description: "the ID of the received message"},
			{name: "text", kind: "string", required: true, description: "the reply; line breaks are kept"},
			{name: "report", kind: "string", enum: []string{"none", "delivered", "read", "both"}, def: "both",
				description: "which disposition notifications to ask the recipient for"},
			{name: "wait", kind: "string", enum: []string{"none", "sent", "delivered", "read"}, def: "sent",
				description: "return once the reply is sent, delivered or read; none returns at once"},
			timeoutParam(30),
		},
		acts: true,
		run:  (*daemon).toolReplyMessage,
	},
	{
		name:        "message_status",
		description: "What is known of a sent or received message or file: network answer, delivered, read, file state.",
		params:      []param{{name: "message_id", kind: "string", required: true, description: "the message ID"}},
		run:         (*daemon).toolMessageStatus,
	},
	{
		name: "inbox",
		description: "Received one-to-one messages and file offers, oldest first. Without 'since', the latest ones; " +
			"with it, those after that sequence number (pass the last_seq of the previous call to read on).",
		params: []param{
			{name: "since", kind: "integer", description: "only items with a higher seq"},
			{name: "from", kind: "string", description: "only from this MCData ID"},
			{name: "kind", kind: "string", enum: []string{"any", "message", "file"}, def: "any", description: "messages, files or both"},
			{name: "unread_only", kind: "boolean", def: false, description: "only messages not marked read"},
			{name: "limit", kind: "integer", def: 20, description: "at most this many items (1 to 200)"},
		},
		run: (*daemon).toolInbox,
	},
	{
		name: "wait_message",
		description: "Wait for the next received one-to-one message or file offer and return it; returns timed_out " +
			"when none comes. Without 'since', only what arrives after the call.",
		params: []param{
			{name: "from", kind: "string", description: "only from this MCData ID"},
			{name: "kind", kind: "string", enum: []string{"any", "message", "file"}, def: "any", description: "messages, files or both"},
			{name: "since", kind: "integer", description: "return the first item with a higher seq, even one already received"},
			timeoutParam(60),
		},
		run: (*daemon).toolWaitMessage,
	},
	{
		name: "mark_read",
		description: "Mark a received message read. Sends the read report when the sender asked for one; " +
			"reading the inbox never does.",
		params: []param{{name: "message_id", kind: "string", required: true, description: "the received message's ID"}},
		acts:   true,
		run:    (*daemon).toolMarkRead,
	},
	{
		name: "send_file",
		description: "Send a file from the agent's outbox directory to an MCData user (file distribution over HTTP), " +
			"as the agent's functional alias. Returns once the file is uploaded and offered.",
		params: []param{
			{name: "to", kind: "string", required: true, description: "the recipient's MCData ID, a SIP URI"},
			{name: "file", kind: "string", required: true, description: "the file's name in the outbox directory"},
			{name: "mandatory", kind: "boolean", def: false, description: "the recipient downloads it without asking its user"},
			timeoutParam(60),
		},
		acts: true,
		run:  (*daemon).toolSendFile,
	},
	{
		name:        "respond_file",
		description: "Accept (download), reject or defer a received file offer. Accept returns the downloaded file's path.",
		params: []param{
			{name: "message_id", kind: "string", required: true, description: "the file offer's message ID"},
			{name: "action", kind: "string", required: true, enum: []string{"accept", "reject", "defer"}, description: "what to do"},
			timeoutParam(120),
		},
		acts: true,
		run:  (*daemon).toolRespondFile,
	},
}

func timeout(a args) (time.Duration, error) {
	s := a.i("timeout_s")
	if s < 1 || s > 300 {
		return 0, fail(codeInvalidArgument, "timeout_s is 1 to 300")
	}
	return time.Duration(s) * time.Second, nil
}

func (d *daemon) toolStatus(args) (any, error) {
	st := d.latest()
	d.mu.Lock()
	ready, alias := d.ready, d.alias
	d.mu.Unlock()
	running, _ := d.eng.running()
	out := map[string]any{
		"version":           version,
		"ready":             ready,
		"engine_running":    running,
		"mcdata_id":         d.eng.userID,
		"registered":        st["registered"] == true,
		"mcdata_authorized": st["mcdata_authorized"] == true,
		"last_error":        st["last_error"],
	}
	if exit := d.eng.lastExit(); exit != "" && !running {
		out["engine_exit"] = exit
	}
	if d.cfg.Alias != "" {
		out["alias"] = map[string]any{"id": d.cfg.Alias, "state": alias, "active": d.aliasActive(st), "required": *d.cfg.RequireAlias}
	}
	_, err := d.sendingAlias()
	out["can_send"] = ready && err == nil
	if err != nil {
		out["cannot_send_because"] = err.Error()
	}
	d.store.view(func() {
		unread := 0
		for _, m := range d.store.In {
			if m.Kind == "message" && !m.Read {
				unread++
			}
		}
		out["unread"] = unread
		out["last_seq"] = d.store.NextSeq - 1
	})
	return out, nil
}

// checkRecipient refuses an ID that is not a SIP URI, or that neither the recipients of the
// configuration nor its sender window (a user who wrote to the agent lately) allow.
func (d *daemon) checkRecipient(to string) error {
	if !validURI(to) {
		return fail(codeInvalidArgument, "%q is not an MCData ID (sip:user@domain)", to)
	}
	if d.cfg.allowed(to) || d.recentSender(to) {
		return nil
	}
	return fail(codeNotAllowed, "%s is not among the recipients the agent may send to", to)
}

// recentSender reports whether id sent the agent something within allow_senders_hours.
func (d *daemon) recentSender(id string) bool {
	if d.cfg.AllowSendersHours <= 0 {
		return false
	}
	since := time.Now().Add(-time.Duration(d.cfg.AllowSendersHours) * time.Hour)
	found := false
	d.store.view(func() {
		for i := len(d.store.In) - 1; i >= 0 && !found; i-- {
			found = d.store.In[i].From == id && d.store.In[i].Received.After(since)
		}
	})
	return found
}

// engineText is text as the engine's escaped option takes it: one line, with line breaks
// as \n, tabs as \t and backslashes as \\.
var engineText = strings.NewReplacer("\\", "\\\\", "\n", "\\n", "\t", "\\t").Replace

// sentEvent finds the engine's report of what it sent to target: the event kind and its
// message_id.
func sentEvent(kind, target string) func([]engineEvent, map[string]any) (any, bool, error) {
	return func(events []engineEvent, _ map[string]any) (any, bool, error) {
		for _, ev := range events {
			if ev.Kind == kind && str(ev.Fields["target"]) == target {
				return str(ev.Fields["message_id"]), true, nil
			}
			if ev.Kind == "mcdata_file_upload_refused" {
				return nil, false, fail(codeTransferFailed, "the content server refused the upload: HTTP %v", ev.Fields["status"])
			}
		}
		return nil, false, nil
	}
}

var reportWords = map[string]string{"none": "", "delivered": "delivery ", "read": "read ", "both": "both "}

// checkWait refuses to wait for a report the message does not ask for.
func checkWait(report, wait string) error {
	if wait == "delivered" && report != "delivered" && report != "both" ||
		wait == "read" && report != "read" && report != "both" {
		return fail(codeInvalidArgument, "waiting for %s needs report %s or both", wait, wait)
	}
	return nil
}

func (d *daemon) toolSendMessage(a args) (any, error) {
	to, report, wait := a.s("to"), a.s("report"), a.s("wait")
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	if err := d.checkRecipient(to); err != nil {
		return nil, err
	}
	text, err := d.cfg.checkText(a.s("text"))
	if err != nil {
		return nil, err
	}
	if err := checkWait(report, wait); err != nil {
		return nil, err
	}
	alias, err := d.sendingAlias()
	if err != nil {
		return nil, err
	}
	if err := d.limit.take(to); err != nil {
		return nil, err
	}
	id, err := d.transact("sds "+reportWords[report]+"escaped "+to+" "+engineText(text), 10*time.Second,
		sentEvent("mcdata_sds_sent", to))
	if err != nil {
		return nil, err
	}
	m := &outbound{Kind: "message", MessageID: id.(string), To: to, Alias: alias, Text: text, Report: report, Sent: time.Now()}
	if err := d.store.addOut(m); err != nil {
		return nil, err
	}
	return d.awaitOutbound(m.MessageID, wait, limit)
}

func (d *daemon) toolReplyMessage(a args) (any, error) {
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	id, report, wait := a.s("message_id"), a.s("report"), a.s("wait")
	if !validMessageID(id) {
		return nil, fail(codeInvalidArgument, "%q is not a message ID", id)
	}
	if err := checkWait(report, wait); err != nil {
		return nil, err
	}
	var original inbound
	found := false
	d.store.view(func() {
		if m := d.store.findIn(id); m != nil && m.Kind == "message" {
			original, found = *m, true
		}
	})
	if !found {
		return nil, fail(codeNotFound, "no received message %s", id)
	}
	if err := d.checkRecipient(original.From); err != nil {
		return nil, err
	}
	text, err := d.cfg.checkText(a.s("text"))
	if err != nil {
		return nil, err
	}
	alias, err := d.sendingAlias()
	if err != nil {
		return nil, err
	}
	if err := d.limit.take(original.From); err != nil {
		return nil, err
	}
	sent, err := d.transact("sds-reply "+reportWords[report]+"escaped "+id+" "+engineText(text), 10*time.Second,
		sentEvent("mcdata_sds_sent", original.From))
	if err != nil {
		return nil, err
	}
	m := &outbound{Kind: "message", MessageID: sent.(string), To: original.From, Alias: alias, Text: text,
		Report: report, Sent: time.Now()}
	if err := d.store.addOut(m); err != nil {
		return nil, err
	}
	return d.awaitOutbound(m.MessageID, wait, limit)
}

// awaitOutbound waits until a sent message or file reaches the wanted stage (none, sent,
// delivered or read) and returns what is known of it. A failure is an error naming the
// message.
func (d *daemon) awaitOutbound(id, wait string, limit time.Duration) (any, error) {
	var m outbound
	reached := func() bool {
		switch wait {
		case "sent":
			return m.SIPStatus > 0
		case "delivered":
			return m.Delivered
		case "read":
			return m.Read
		}
		return true
	}
	err := d.changed.waitUntil(limit, func() (bool, error) {
		d.store.view(func() { m = *d.store.findOut(id) })
		return m.Failed != "" || reached(), nil
	})
	if m.Failed != "" {
		return nil, fail(codeSendFailed, "message %s to %s failed: %s", id, m.To, m.Failed)
	}
	out := map[string]any{"message": m, "waited_for": wait, "reached": err == nil}
	if err != nil {
		out["note"] = fmt.Sprintf("not %s within %s; message_status tells later", wait, limit)
	}
	return out, nil
}

func (d *daemon) toolMessageStatus(a args) (any, error) {
	id := a.s("message_id")
	var out any
	d.store.view(func() {
		if m := d.store.findOut(id); m != nil {
			out = map[string]any{"direction": "sent", "message": *m}
		} else if m := d.store.findIn(id); m != nil {
			out = map[string]any{"direction": "received", "message": *m}
		}
	})
	if out == nil {
		return nil, fail(codeNotFound, "no message %s", id)
	}
	return out, nil
}

// matches reports whether a received item passes the from and kind filters.
func matches(m *inbound, from, kind string) bool {
	return (from == "" || m.From == from) && (kind == "any" || m.Kind == kind)
}

func (d *daemon) toolInbox(a args) (any, error) {
	limit := a.i("limit")
	if limit < 1 || limit > 200 {
		return nil, fail(codeInvalidArgument, "limit is 1 to 200")
	}
	var items []inbound
	var last uint64
	d.store.view(func() {
		last = d.store.NextSeq - 1
		for _, m := range d.store.In {
			if a.has("since") && m.Seq <= uint64(max(a.i("since"), 0)) || !matches(m, a.s("from"), a.s("kind")) ||
				a.b("unread_only") && (m.Kind != "message" || m.Read) {
				continue
			}
			items = append(items, *m)
		}
	})
	more := len(items) > limit
	if more && a.has("since") {
		items = items[:limit] // read on from the oldest
	} else if more {
		items = items[len(items)-limit:] // the latest
	}
	if items == nil {
		items = []inbound{}
	}
	next := last
	if more && a.has("since") {
		next = items[len(items)-1].Seq
	}
	return map[string]any{"items": items, "last_seq": next, "more": more && a.has("since")}, nil
}

func (d *daemon) toolWaitMessage(a args) (any, error) {
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	var since uint64
	d.store.view(func() { since = d.store.NextSeq - 1 })
	if a.has("since") {
		since = uint64(max(a.i("since"), 0))
	}
	var found *inbound
	err = d.changed.waitUntil(limit, func() (bool, error) {
		d.store.view(func() {
			for _, m := range d.store.In {
				if m.Seq > since && matches(m, a.s("from"), a.s("kind")) {
					copied := *m
					found = &copied
					return
				}
			}
		})
		return found != nil, nil
	})
	if errors.Is(err, errTimeout) {
		return map[string]any{"timed_out": true, "last_seq": since}, nil
	}
	return map[string]any{"timed_out": false, "item": found, "last_seq": found.Seq}, nil
}

// engineEntry is the entry of the engine's status list key whose message_id is id.
func engineEntry(st map[string]any, key, id string) map[string]any {
	for _, entry := range list(st[key]) {
		if str(entry["message_id"]) == id {
			return entry
		}
	}
	return nil
}

func (d *daemon) toolMarkRead(a args) (any, error) {
	id := a.s("message_id")
	if !validMessageID(id) {
		return nil, fail(codeInvalidArgument, "%q is not a message ID", id)
	}
	var m inbound
	found := false
	d.store.view(func() {
		if in := d.store.findIn(id); in != nil && in.Kind == "message" {
			m, found = *in, true
		}
	})
	if !found {
		return nil, fail(codeNotFound, "no received message %s", id)
	}
	if m.Read {
		return map[string]any{"message_id": id, "read": true, "read_report": "already marked read"}, nil
	}
	report := "sent"
	if m.ReportWanted != "read" && m.ReportWanted != "both" {
		report = "not asked for by the sender"
	}
	_, err := d.transact("sds-display "+id, 5*time.Second, func(_ []engineEvent, st map[string]any) (any, bool, error) {
		entry := engineEntry(st, "mcdata_inbox", id)
		return nil, entry != nil && entry["displayed"] == true, nil
	})
	if err != nil {
		// The engine keeps the last 50 messages; an older one can only be marked read here.
		report = "not sent: " + err.Error()
	}
	if err := d.store.update(func() bool { d.store.findIn(id).Read = true; return true }); err != nil {
		return nil, err
	}
	return map[string]any{"message_id": id, "read": true, "read_report": report}, nil
}

func (d *daemon) toolSendFile(a args) (any, error) {
	to := a.s("to")
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	if err := d.checkRecipient(to); err != nil {
		return nil, err
	}
	file, err := d.cfg.checkFile(a.s("file"))
	if err != nil {
		return nil, err
	}
	alias, err := d.sendingAlias()
	if err != nil {
		return nil, err
	}
	if err := d.limit.take(to); err != nil {
		return nil, err
	}
	line := "fd-send " + to + " " + file
	if a.b("mandatory") {
		line = "fd-send mandatory " + to + " " + file
	}
	id, err := d.transact(line, limit, sentEvent("mcdata_file_sent", to))
	if err != nil {
		return nil, err
	}
	m := &outbound{Kind: "file", MessageID: id.(string), To: to, Alias: alias, Path: file, Report: "fd", Sent: time.Now()}
	if err := d.store.addOut(m); err != nil {
		return nil, err
	}
	return d.awaitOutbound(m.MessageID, "sent", 10*time.Second)
}

// fileOutcomes are the engine's file states that end each decision.
var fileOutcomes = map[string][]string{
	"accept": {"downloaded", "download-failed", "hash-mismatch", "unsaved"},
	"reject": {"rejected"},
	"defer":  {"deferred"},
}

func (d *daemon) toolRespondFile(a args) (any, error) {
	id, action := a.s("message_id"), a.s("action")
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	if !validMessageID(id) {
		return nil, fail(codeInvalidArgument, "%q is not a message ID", id)
	}
	found := false
	d.store.view(func() { found = d.store.findIn(id) != nil && d.store.findIn(id).Kind == "file" })
	if !found {
		return nil, fail(codeNotFound, "no received file offer %s", id)
	}
	result, err := d.transact("fd-"+action+" "+id, limit, func(events []engineEvent, st map[string]any) (any, bool, error) {
		for _, ev := range events {
			if ev.Kind == "mcdata_file_deferral_ignored" {
				return nil, false, fail(codeEngineRefused, "too late to defer: the offer's timer has run out; accept or reject it")
			}
		}
		entry := engineEntry(st, "mcdata_files", id)
		if entry == nil || !slices.Contains(fileOutcomes[action], str(entry["state"])) {
			return nil, false, nil
		}
		return entry, true, nil
	})
	if err != nil {
		return nil, err
	}
	entry := result.(map[string]any)
	out := map[string]any{"message_id": id, "state": entry["state"]}
	if action == "accept" {
		if entry["state"] != "downloaded" {
			return nil, fail(codeTransferFailed, "file %s: %v", id, entry["state"])
		}
		out["path"], out["bytes"], out["sha256"] = entry["path"], entry["plain_bytes"], entry["plain_sha256"]
	}
	return out, nil
}
