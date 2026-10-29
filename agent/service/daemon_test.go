//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const (
	agentID = "sip:agent.mcdata@example.org"
	alias   = "sip:ops-bot.fa@example.org"
	alice   = "sip:alice.mcdata@example.org"
)

// startDaemon runs a daemon on the fake engine and waits until it can send.
func startDaemon(t *testing.T, fake map[string]any, edit func(*config)) *daemon {
	t.Helper()
	return startDaemonWith(t, nil, fake, edit)
}

// startDaemonWith is startDaemon with more of the engine configuration (a nil part removed).
func startDaemonWith(t *testing.T, extra, fake map[string]any, edit func(*config)) *daemon {
	t.Helper()
	dir := t.TempDir()
	engineConfig := map[string]any{
		"control_fifo": filepath.Join(dir, "control"), "status_file": filepath.Join(dir, "status.json"),
		"mcdata": map[string]any{"user_id": agentID}, "fake": fake,
	}
	for key, v := range extra {
		if v == nil {
			delete(engineConfig, key)
		} else {
			engineConfig[key] = v
		}
	}
	raw, _ := json.Marshal(engineConfig)
	if err := os.WriteFile(filepath.Join(dir, "engine.json"), raw, 0o600); err != nil {
		t.Fatal(err)
	}
	enginePath, _ := filepath.Abs("testdata/fake-engine")
	cfg := &config{DataDir: filepath.Join(dir, "agent"), Alias: alias, Recipients: []string{"sip:*.mcdata@example.org"},
		RatePerMin: 100, RatePerRecipient: 100, MaxTextBytes: 1000}
	cfg.Engine.Path, cfg.Engine.Config = enginePath, filepath.Join(dir, "engine.json")
	cfg.OutboxDir = filepath.Join(cfg.DataDir, "outbox")
	if edit != nil {
		edit(cfg)
	}
	if cfg.RequireAlias == nil {
		need := cfg.Alias != ""
		cfg.RequireAlias = &need
	}
	d, err := newDaemon(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(d.shutdown)
	go d.poll()
	go d.supervise()
	err = d.changed.waitUntil(10*time.Second, func() (bool, error) {
		d.mu.Lock()
		defer d.mu.Unlock()
		return d.ready && (d.cfg.Alias == "" || (d.alias != "" && d.alias != "activating")), nil
	})
	if err != nil {
		t.Fatalf("the daemon did not get ready: %v", err)
	}
	return d
}

func call(t *testing.T, d *daemon, tool string, args map[string]any) map[string]any {
	t.Helper()
	result, err := d.call(tool, args)
	if err != nil {
		t.Fatalf("%s: %v", tool, err)
	}
	// What the CLI and MCP see: the result through JSON.
	raw, _ := json.Marshal(result)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return out
}

// callCode expects the tool to fail with this code and a message containing want.
func callCode(t *testing.T, d *daemon, tool string, args map[string]any, code, want string) {
	t.Helper()
	_, err := d.call(tool, args)
	if err == nil || errorCode(err) != code || !strings.Contains(err.Error(), want) {
		t.Fatalf("%s: error %v (%s), want %s containing %q", tool, err, errorCode(err), code, want)
	}
}

func callError(t *testing.T, d *daemon, tool string, args map[string]any, want string) {
	t.Helper()
	_, err := d.call(tool, args)
	if err == nil || !strings.Contains(err.Error(), want) {
		t.Fatalf("%s: error %v, want one containing %q", tool, err, want)
	}
}

func TestStatusAndAlias(t *testing.T) {
	d := startDaemon(t, nil, nil)
	st := call(t, d, "status", nil)
	a, _ := st["alias"].(map[string]any)
	if st["can_send"] != true || st["mcdata_id"] != agentID || a["active"] != true || a["state"] != "active" {
		t.Fatalf("status %v", st)
	}
}

func TestAliasRefusedBlocksSending(t *testing.T) {
	d := startDaemon(t, map[string]any{"alias_outcome": "take-over-possible"}, nil)
	st := call(t, d, "status", nil)
	if st["can_send"] != false || !strings.Contains(st["cannot_send_because"].(string), "take-over-possible") {
		t.Fatalf("status %v", st)
	}
	callError(t, d, "send_message", map[string]any{"to": alice, "text": "hi"}, "functional alias")
}

func TestAliasLostAndRegained(t *testing.T) {
	d := startDaemon(t, nil, func(c *config) { c.AliasRetryS = 1 })
	_ = d.eng.command("fake-alias-outcome take-over-possible")
	_ = d.eng.command("fake-lose-alias " + alias)
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		return call(t, d, "status", nil)["can_send"] == false, nil
	}); err != nil {
		t.Fatal("the lost alias still allows sending")
	}
	callError(t, d, "send_message", map[string]any{"to": alice, "text": "x"}, "functional alias")
	_ = d.eng.command("fake-alias-outcome activated")
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		return call(t, d, "status", nil)["can_send"] == true, nil
	}); err != nil {
		t.Fatalf("the alias was not activated again: %v", call(t, d, "status", nil))
	}
}

// The earlier refusal stays in the engine's state until the new activation's outcome lands: it is not this
// activation's outcome.
func TestAliasActivatedAfterRefusalLandsLate(t *testing.T) {
	d := startDaemon(t, map[string]any{"alias_outcome": "refused"}, func(c *config) { c.AliasRetryS = 1 })
	if st := call(t, d, "status", nil); st["can_send"] != false {
		t.Fatalf("status %v", st)
	}
	_ = d.eng.command("fake-alias-delay 1500")
	_ = d.eng.command("fake-alias-outcome activated")
	if err := d.changed.waitUntil(10*time.Second, func() (bool, error) {
		return call(t, d, "status", nil)["can_send"] == true, nil
	}); err != nil {
		t.Fatalf("the alias active in the engine is not used: %v", call(t, d, "status", nil))
	}
}

func TestSendMessage(t *testing.T) {
	d := startDaemon(t, nil, nil)
	out := call(t, d, "send_message", map[string]any{"to": alice, "text": "  unit 4 report position  ", "wait": "delivered"})
	m := out["message"].(map[string]any)
	if out["reached"] != true || m["sip_status"] != 200.0 || m["delivered"] != true || m["alias"] != alias ||
		m["text"] != "unit 4 report position" || m["report"] != "both" {
		t.Fatalf("send_message %v", out)
	}
	id := m["message_id"].(string)
	// The read report arrives later.
	go func() { time.Sleep(300 * time.Millisecond); _ = d.eng.command("fake-read " + id) }()
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		st := call(t, d, "message_status", map[string]any{"message_id": id})
		return st["message"].(map[string]any)["read"] == true, nil
	}); err != nil {
		t.Fatal("no read report")
	}
	callCode(t, d, "send_message", map[string]any{"to": "sip:refuse.mcdata@example.org", "text": "x"},
		codeSendFailed, "SIP 403: 403 mcdata.test \"229 one-to-one target not allowed\"")
	callError(t, d, "send_message", map[string]any{"to": alice, "text": "x", "report": "none", "wait": "read"}, "needs report")
}

func TestSendMessageRefusals(t *testing.T) {
	d := startDaemon(t, nil, nil)
	cases := []struct {
		args map[string]any
		want string
	}{
		{map[string]any{"to": "sip:bob.mcptt@example.org", "text": "x"}, "not among the recipients"},
		{map[string]any{"to": alice + " read", "text": "x"}, "not an MCData ID"},
		{map[string]any{"to": "both", "text": "x"}, "not an MCData ID"},
		{map[string]any{"to": alice, "text": "   "}, "empty"},
		{map[string]any{"to": alice, "text": strings.Repeat("x", 1001)}, "more than 1000"},
		{map[string]any{"to": alice, "text": "x", "priority": 5.0}, "no argument"},
		{map[string]any{"to": alice}, "needs \"text\""},
		{map[string]any{"to": alice, "text": "x", "wait": "forever"}, "one of"},
	}
	for _, c := range cases {
		callError(t, d, "send_message", c.args, c.want)
	}
}

func TestRateLimit(t *testing.T) {
	d := startDaemon(t, nil, func(c *config) { c.RatePerMin, c.RatePerRecipient = 3, 2 })
	for range 2 {
		call(t, d, "send_message", map[string]any{"to": alice, "text": "x", "report": "none"})
	}
	callCode(t, d, "send_message", map[string]any{"to": alice, "text": "x"}, codeRateLimited, "a minute to "+alice)
	bob := "sip:bob.mcdata@example.org"
	call(t, d, "send_message", map[string]any{"to": bob, "text": "x", "report": "none"})
	callCode(t, d, "send_message", map[string]any{"to": bob, "text": "x"}, codeRateLimited, "3 sends a minute")
}

func TestReceiveReadReply(t *testing.T) {
	d := startDaemon(t, map[string]any{"called_alias": alias}, nil)
	// Group SDS are left out; a one-to-one SDS is the next message.
	go func() {
		time.Sleep(200 * time.Millisecond)
		_ = d.eng.command("fake-group-sds " + alice + " sip:ops@example.org all units")
		_ = d.eng.command("fake-sds " + alice + " 3 need backup at gate 2")
	}()
	got := call(t, d, "wait_message", map[string]any{"timeout_s": 5.0})
	item := got["item"].(map[string]any)
	if got["timed_out"] != false || item["text"] != "need backup at gate 2" || item["from"] != alice ||
		item["read"] != false || item["report_wanted"] != "both" || item["to_alias"] != alias {
		t.Fatalf("wait_message %v", got)
	}
	id := item["message_id"].(string)
	inbox := call(t, d, "inbox", map[string]any{"unread_only": true})
	if items := inbox["items"].([]any); len(items) != 1 {
		t.Fatalf("inbox %v", inbox)
	}
	read := call(t, d, "mark_read", map[string]any{"message_id": id})
	if read["read_report"] != "sent" {
		t.Fatalf("mark_read %v", read)
	}
	if entry := engineEntry(d.latest(), "mcdata_inbox", id); entry["displayed"] != true {
		t.Fatal("the engine was not told the message was displayed")
	}
	if inbox := call(t, d, "inbox", map[string]any{"unread_only": true}); len(inbox["items"].([]any)) != 0 {
		t.Fatalf("inbox after mark_read %v", inbox)
	}
	reply := call(t, d, "reply_message", map[string]any{"message_id": id, "text": "on my way"})
	if m := reply["message"].(map[string]any); m["to"] != alice || m["sip_status"] != 200.0 {
		t.Fatalf("reply_message %v", reply)
	}
	callError(t, d, "mark_read", map[string]any{"message_id": "x; quit"}, "not a message ID")
	if timeout := call(t, d, "wait_message", map[string]any{"timeout_s": 1.0}); timeout["timed_out"] != true {
		t.Fatalf("wait_message %v", timeout)
	}
}

func TestMarkReadOfAMessageTheEngineDropped(t *testing.T) {
	d := startDaemon(t, nil, nil)
	_ = d.eng.command("fake-sds " + alice + " 2 hello")
	got := call(t, d, "wait_message", map[string]any{"timeout_s": 5.0, "since": 0.0})
	id := got["item"].(map[string]any)["message_id"].(string)
	_ = d.eng.command("fake-forget " + id)
	time.Sleep(300 * time.Millisecond)
	read := call(t, d, "mark_read", map[string]any{"message_id": id})
	if read["read"] != true || !strings.Contains(read["read_report"].(string), "no received SDS message") {
		t.Fatalf("mark_read %v", read)
	}
}

func TestInboxPaging(t *testing.T) {
	d := startDaemon(t, nil, nil)
	for i := range 5 {
		_ = d.eng.command("fake-sds " + alice + " 0 m" + string(rune('0'+i)))
	}
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		return call(t, d, "status", nil)["last_seq"] == 5.0, nil
	}); err != nil {
		t.Fatal("messages not received")
	}
	latest := call(t, d, "inbox", map[string]any{"limit": 2.0})
	if items := latest["items"].([]any); len(items) != 2 || items[1].(map[string]any)["text"] != "m4" {
		t.Fatalf("latest %v", latest)
	}
	page := call(t, d, "inbox", map[string]any{"since": 0.0, "limit": 3.0})
	if items := page["items"].([]any); len(items) != 3 || page["last_seq"] != 3.0 || page["more"] != true {
		t.Fatalf("first page %v", page)
	}
	page = call(t, d, "inbox", map[string]any{"since": 3.0, "limit": 3.0})
	if items := page["items"].([]any); len(items) != 2 || page["last_seq"] != 5.0 || page["more"] != false {
		t.Fatalf("second page %v", page)
	}
}

func TestFiles(t *testing.T) {
	d := startDaemon(t, nil, nil)
	if err := os.WriteFile(filepath.Join(d.cfg.OutboxDir, "report.pdf"), []byte("pdf"), 0o600); err != nil {
		t.Fatal(err)
	}
	sent := call(t, d, "send_file", map[string]any{"to": alice, "file": "report.pdf"})
	if m := sent["message"].(map[string]any); m["sip_status"] != 200.0 || m["kind"] != "file" {
		t.Fatalf("send_file %v", sent)
	}
	callError(t, d, "send_file", map[string]any{"to": alice, "file": "/etc/passwd"}, "only files directly in")
	callError(t, d, "send_file", map[string]any{"to": alice, "file": "../store.json"}, "only files directly in")
	callError(t, d, "send_file", map[string]any{"to": alice, "file": "a b.pdf"}, "file name")
	if err := os.Symlink("/etc/hostname", filepath.Join(d.cfg.OutboxDir, "link.txt")); err != nil {
		t.Fatal(err)
	}
	callError(t, d, "send_file", map[string]any{"to": alice, "file": "link.txt"}, "only files directly in")

	_ = d.eng.command("fake-file " + alice + " map.png")
	got := call(t, d, "wait_message", map[string]any{"timeout_s": 5.0, "kind": "file", "since": 0.0})
	item := got["item"].(map[string]any)
	if item["file_name"] != "map.png" || item["file_state"] != "offered" {
		t.Fatalf("file offer %v", got)
	}
	accepted := call(t, d, "respond_file", map[string]any{"message_id": item["message_id"], "action": "accept"})
	data, err := os.ReadFile(accepted["path"].(string))
	if err != nil || string(data) != "contents of map.png" || accepted["state"] != "downloaded" {
		t.Fatalf("respond_file %v %v", accepted, err)
	}
}

func TestStorePersists(t *testing.T) {
	d := startDaemon(t, nil, nil)
	_ = d.eng.command("fake-sds " + alice + " 0 kept")
	call(t, d, "wait_message", map[string]any{"timeout_s": 5.0, "since": 0.0})
	reopened, err := openStore(d.store.path, newNotifier())
	if err != nil || len(reopened.In) != 1 || reopened.In[0].Text != "kept" || reopened.NextSeq != 2 {
		t.Fatalf("reopened store %+v %v", reopened, err)
	}
}

func TestAuditLog(t *testing.T) {
	d := startDaemon(t, nil, nil)
	call(t, d, "send_message", map[string]any{"to": alice, "text": "audited", "report": "none"})
	call(t, d, "status", nil)
	callError(t, d, "send_message", map[string]any{"to": "sip:x@evil.org", "text": "no"}, "not among")
	raw, _ := os.ReadFile(filepath.Join(d.cfg.DataDir, "audit.jsonl"))
	lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], "audited") || !strings.Contains(lines[1], "\"error\"") {
		t.Fatalf("audit log %q", raw)
	}
}

// sentRecord is the fake engine's record of the SDS it sent with this message ID.
func sentRecord(d *daemon, id string) map[string]any {
	return engineEntry(d.latest(), "mcdata_sent", id)
}

func TestMultiLineText(t *testing.T) {
	d := startDaemon(t, nil, nil)
	for _, text := range []string{
		"line one\nline two\n\tindented",
		"one\nquit",               // a second line is text, never a second command
		`a literal \n and C:\dir`, // backslashes arrive as written
		"windows\r\nline\rbreaks",
	} {
		out := call(t, d, "send_message", map[string]any{"to": alice, "text": text, "report": "none"})
		id := out["message"].(map[string]any)["message_id"].(string)
		want := strings.TrimSpace(lineBreaks.Replace(text))
		if got := sentRecord(d, id)["text"]; got != want {
			t.Fatalf("sent %q, the engine sent %q", want, got)
		}
	}
	if st := d.latest(); st["last_error"] != nil {
		t.Fatalf("the engine reported %v", st["last_error"])
	}
}

func TestReplyWithReports(t *testing.T) {
	d := startDaemon(t, nil, nil)
	_ = d.eng.command("fake-sds " + alice + " 0 status?")
	got := call(t, d, "wait_message", map[string]any{"timeout_s": 5.0, "since": 0.0})
	id := got["item"].(map[string]any)["message_id"].(string)
	reply := call(t, d, "reply_message", map[string]any{"message_id": id, "text": "all clear\ngate 2 open", "wait": "delivered"})
	m := reply["message"].(map[string]any)
	if m["delivered"] != true || m["report"] != "both" {
		t.Fatalf("reply_message %v", reply)
	}
	if record := sentRecord(d, m["message_id"].(string)); record["disposition"] != 3.0 || record["text"] != "all clear\ngate 2 open" {
		t.Fatalf("the engine sent %v", record)
	}
}

func TestSenderWindow(t *testing.T) {
	d := startDaemon(t, nil, func(c *config) { c.Recipients, c.AllowSendersHours = nil, 1 })
	carol := "sip:carol.mcdata@example.org"
	callCode(t, d, "send_message", map[string]any{"to": carol, "text": "hi"}, codeNotAllowed, "not among")
	_ = d.eng.command("fake-sds " + carol + " 0 anyone there?")
	call(t, d, "wait_message", map[string]any{"timeout_s": 5.0, "since": 0.0})
	call(t, d, "send_message", map[string]any{"to": carol, "text": "yes, the ops agent", "report": "none"})
	callCode(t, d, "send_message", map[string]any{"to": alice, "text": "hi"}, codeNotAllowed, "not among")
}

func TestInboundMetadata(t *testing.T) {
	d := startDaemon(t, nil, nil)
	_ = d.eng.command("fake-emergency-sds " + alice + " officer down at gate 2")
	got := call(t, d, "wait_message", map[string]any{"timeout_s": 5.0, "since": 0.0})
	item := got["item"].(map[string]any)
	if item["emergency"] != true || item["used_priority"] != 9.0 || item["authenticated"] != true {
		t.Fatalf("wait_message %v", item)
	}
}

func TestErrorCodes(t *testing.T) {
	d := startDaemon(t, nil, nil)
	callCode(t, d, "nope", nil, codeUnknownTool, "unknown tool")
	callCode(t, d, "send_message", map[string]any{"to": alice}, codeInvalidArgument, "needs")
	callCode(t, d, "send_message", map[string]any{"to": "sip:x@evil.org", "text": "x"}, codeNotAllowed, "not among")
	callCode(t, d, "message_status", map[string]any{"message_id": "6f1c0000-0000-4000-8000-000000000000"}, codeNotFound, "no message")
	callCode(t, d, "send_file", map[string]any{"to": alice, "file": "missing.pdf"}, codeNotFound, "not in the outbox")
	callCode(t, d, "mark_read", map[string]any{"message_id": "6f1c0000-0000-4000-8000-000000000000"}, codeNotFound, "no received")
	for code, status := range map[string]int{codeInvalidArgument: 2, codeNotAllowed: 3, codeRateLimited: 3,
		codeAliasInactive: 3, codeNotReady: 4, codeSendFailed: 1, codeTimeout: 1} {
		if exitStatus(code) != status {
			t.Fatalf("exit status of %s is %d, want %d", code, exitStatus(code), status)
		}
	}
}
