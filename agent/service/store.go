//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"errors"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// inbound is a one-to-one SDS or file offer the agent received. Seq numbers only grow, so
// that "since" can resume a reading.
type inbound struct {
	Seq            uint64    `json:"seq"`
	Kind           string    `json:"kind"` // "message" or "file"
	MessageID      string    `json:"message_id"`
	ConversationID string    `json:"conversation_id,omitempty"`
	InReplyTo      string    `json:"in_reply_to,omitempty"`
	From           string    `json:"from"`
	SenderAlias    string    `json:"sender_alias,omitempty"` // the functional alias the sender presented
	ToAlias        string    `json:"to_alias,omitempty"`     // the agent's functional alias the message was sent to
	Text           string    `json:"text,omitempty"`
	ReportWanted   string    `json:"report_wanted,omitempty"`  // what the sender asked: delivered, read or both
	Emergency      bool      `json:"emergency,omitempty"`      // sent as an emergency communication
	ImminentPeril  bool      `json:"imminent_peril,omitempty"` // sent as an imminent peril communication
	UsedPriority   *int      `json:"used_priority,omitempty"`  // the priority the server applied
	Authenticated  bool      `json:"authenticated"`            // the payload's signature was verified (TS 33.180)
	Protected      bool      `json:"protected"`                // the payload was protected end to end
	Read           bool      `json:"read"`
	Received       time.Time `json:"received"`
	FileName       string    `json:"file_name,omitempty"`
	FileSize       int64     `json:"file_size,omitempty"`
	Mandatory      bool      `json:"mandatory,omitempty"`
	FileState      string    `json:"file_state,omitempty"` // offered, accepted, downloaded, rejected, deferred, ...
	Path           string    `json:"path,omitempty"`       // where a downloaded file is
}

// outbound is an SDS or file the agent sent, with what the network and the recipient
// reported about it.
type outbound struct {
	Kind      string    `json:"kind"` // "message" or "file"
	MessageID string    `json:"message_id"`
	To        string    `json:"to"`
	Alias     string    `json:"alias,omitempty"` // the functional alias presented
	Text      string    `json:"text,omitempty"`
	Path      string    `json:"path,omitempty"`
	Report    string    `json:"report"` // what was asked of the recipient: none, delivered, read, both
	Sent      time.Time `json:"sent"`
	SIPStatus int       `json:"sip_status,omitempty"` // the final answer to the SIP MESSAGE
	Delivered bool      `json:"delivered"`
	Read      bool      `json:"read"`
	Failed    string    `json:"failed,omitempty"`     // why it did not arrive
	FileState string    `json:"file_state,omitempty"` // the recipient's FD notifications: accepted, rejected, deferred, completed
}

// store holds what the agent sent and received, in a JSON file of its own, because the
// engine keeps only the last 50 of each.
type store struct {
	mu      sync.Mutex
	path    string
	NextSeq uint64               `json:"next_seq"`
	In      []*inbound           `json:"inbound"`
	Out     []*outbound          `json:"outbound"`
	Ignored map[string]time.Time `json:"ignored"` // group messages and files seen and left out
	changed *notifier
}

const storeLimit = 2000

func openStore(path string, changed *notifier) (*store, error) {
	s := &store{path: path, NextSeq: 1, Ignored: map[string]time.Time{}, changed: changed}
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, s); err != nil {
		return nil, err
	}
	if s.Ignored == nil {
		s.Ignored = map[string]time.Time{}
	}
	return s, nil
}

// save writes the store; the caller holds mu.
func (s *store) save() error {
	if len(s.In) > storeLimit {
		s.In = s.In[len(s.In)-storeLimit:]
	}
	if len(s.Out) > storeLimit {
		s.Out = s.Out[len(s.Out)-storeLimit:]
	}
	for id, at := range s.Ignored {
		if time.Since(at) > 24*time.Hour {
			delete(s.Ignored, id)
		}
	}
	raw, err := json.Marshal(s)
	if err != nil {
		return err
	}
	return writeAtomic(s.path, raw)
}

func (s *store) findIn(id string) *inbound {
	for _, m := range s.In {
		if m.MessageID == id {
			return m
		}
	}
	return nil
}

func (s *store) findOut(id string) *outbound {
	for _, m := range s.Out {
		if m.MessageID == id {
			return m
		}
	}
	return nil
}

func (s *store) addOut(m *outbound) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Out = append(s.Out, m)
	err := s.save()
	s.changed.fire()
	return err
}

// view runs f with the store locked; f must not keep the pointers it sees.
func (s *store) view(f func()) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f()
}

// update runs f with the store locked and saves when f reports a change.
func (s *store) update(f func() bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !f() {
		return nil
	}
	err := s.save()
	s.changed.fire()
	return err
}

// dispositionNames are the SDS disposition requests of TS 24.282 table 15.2.3-1 as the
// engine reports them (0 to 3).
var dispositionNames = []string{"none", "delivered", "read", "both"}

// absorb takes into the store what the engine's status reports: new one-to-one SDS and
// file offers, the answers to sent messages, and the recipients' notifications.
func (s *store) absorb(st map[string]any) error {
	return s.update(func() bool {
		changed := false
		for _, entry := range list(st["mcdata_inbox"]) {
			changed = s.absorbMessage(entry) || changed
		}
		for _, entry := range list(st["mcdata_files"]) {
			changed = s.absorbFile(entry) || changed
		}
		for _, entry := range list(st["mcdata_sent"]) {
			m := s.findOut(str(entry["message_id"]))
			if code := integer(entry["status"]); m != nil && code > 0 && m.SIPStatus != code {
				m.SIPStatus = code
				if code >= 300 && m.Failed == "" {
					m.Failed = "SIP " + strconv.Itoa(code)
				}
				changed = true
			}
		}
		// The Warning header field of a refused message, which names the reason.
		if last, _ := st["mcdata_last_result"].(map[string]any); last != nil {
			m := s.findOut(str(last["message_id"]))
			if warning := str(last["warning"]); m != nil && integer(last["status"]) >= 300 && warning != "" &&
				!strings.Contains(m.Failed, ":") {
				m.Failed = "SIP " + strconv.Itoa(integer(last["status"])) + ": " + warning
				changed = true
			}
		}
		if last, _ := st["mcdata_fd_last_result"].(map[string]any); last != nil {
			m := s.findOut(str(last["message_id"]))
			if code := integer(last["status"]); m != nil && m.Kind == "file" && code > 0 && m.SIPStatus != code {
				m.SIPStatus = code
				if code >= 300 && m.Failed == "" {
					m.Failed = "SIP " + strconv.Itoa(code)
				}
				changed = true
			}
		}
		for _, entry := range list(st["mcdata_notifications"]) {
			changed = s.absorbNotification(entry) || changed
		}
		for _, entry := range list(st["mcdata_file_notifications"]) {
			m := s.findOut(str(entry["message_id"]))
			_, typed := entry["type"]
			if m == nil || m.Kind != "file" || !typed {
				continue
			}
			// The FD notification types of TS 24.282 (mcx::FdNotificationType).
			state := map[int]string{1: "accepted", 2: "rejected", 3: "completed", 4: "deferred"}[integer(entry["type"])]
			if state != "" && m.FileState != state && m.FileState != "completed" {
				m.FileState = state
				changed = true
			}
		}
		return changed
	})
}

func (s *store) absorbMessage(entry map[string]any) bool {
	id := str(entry["message_id"])
	if id == "" {
		return false
	}
	if m := s.findIn(id); m != nil {
		return false
	}
	if _, seen := s.Ignored[id]; seen {
		return false
	}
	// One-to-one only: a group or ad hoc group SDS is left out.
	if str(entry["group"]) != "" || str(entry["request_type"]) != "one-to-one-sds" {
		s.Ignored[id] = time.Now()
		return true
	}
	var texts []string
	for _, part := range list(entry["payloads"]) {
		if integer(part["content_type"]) == 1 { // text (mcx::ContentType::Text)
			texts = append(texts, str(part["text"]))
		}
	}
	report := "none"
	if d := integer(entry["disposition"]); d >= 0 && d < len(dispositionNames) {
		report = dispositionNames[d]
	}
	s.In = append(s.In, &inbound{
		Seq: s.NextSeq, Kind: "message", MessageID: id,
		ConversationID: str(entry["conversation_id"]), InReplyTo: str(entry["in_reply_to"]),
		From: str(entry["from"]), SenderAlias: str(entry["functional_alias"]),
		ToAlias: str(entry["called_functional_alias"]), Text: strings.Join(texts, "\n"),
		ReportWanted: report, Emergency: entry["emergency"] == true, ImminentPeril: entry["imminent_peril"] == true,
		UsedPriority: optionalInt(entry["used_priority"]), Authenticated: entry["authenticated"] == true,
		Protected: entry["protected"] == true, Received: time.Now(),
	})
	s.NextSeq++
	return true
}

func (s *store) absorbFile(entry map[string]any) bool {
	id := str(entry["message_id"])
	if id == "" {
		return false
	}
	if m := s.findIn(id); m != nil {
		state, path := str(entry["state"]), str(entry["path"])
		if m.FileState == state && m.Path == path {
			return false
		}
		m.FileState, m.Path = state, path
		return true
	}
	if _, seen := s.Ignored[id]; seen {
		return false
	}
	if str(entry["group"]) != "" {
		s.Ignored[id] = time.Now()
		return true
	}
	s.In = append(s.In, &inbound{
		Seq: s.NextSeq, Kind: "file", MessageID: id, ConversationID: str(entry["conversation_id"]),
		From: str(entry["from"]), FileName: str(entry["name"]), FileSize: int64(integer(entry["size"])),
		Mandatory: entry["mandatory"] == true, FileState: str(entry["state"]), Path: str(entry["path"]),
		Received: time.Now(),
	})
	s.NextSeq++
	return true
}

// absorbNotification applies an SDS disposition notification (TS 24.282 table 15.2.5-1) to
// the message it names.
func (s *store) absorbNotification(entry map[string]any) bool {
	m := s.findOut(str(entry["message_id"]))
	if m == nil || m.Kind != "message" {
		return false
	}
	before := *m
	switch integer(entry["type"]) {
	case 1:
		m.Failed = "undelivered"
	case 2:
		m.Delivered = true
	case 3:
		m.Read = true
	case 4:
		m.Delivered, m.Read = true, true
	case 5:
		m.Failed = "prevented by the system"
	}
	return *m != before
}

func list(v any) []map[string]any {
	items, _ := v.([]any)
	var out []map[string]any
	for _, item := range items {
		if m, ok := item.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

// optionalInt is a JSON number as an int, nil when v is not a number.
func optionalInt(v any) *int {
	if f, ok := v.(float64); ok {
		n := int(f)
		return &n
	}
	return nil
}

// integer is a JSON number as an int, -1 when v is not a number.
func integer(v any) int {
	if f, ok := v.(float64); ok {
		return int(f)
	}
	return -1
}
