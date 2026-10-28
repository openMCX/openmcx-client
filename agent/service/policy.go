//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

// config is the daemon's configuration (agent.json).
type config struct {
	Engine struct {
		Path   string   `json:"path"`   // mcx-client, /usr/local/bin/mcx-client by default
		Config string   `json:"config"` // the engine's configuration, with MCData
		Args   []string `json:"args"`   // further engine arguments, after --config (e.g. --disable-tls)
	} `json:"engine"`
	Socket       string   `json:"socket"`
	DataDir      string   `json:"data_dir"`      // the store and the audit log
	OutboxDir    string   `json:"outbox_dir"`    // the only directory files are sent from; <data_dir>/outbox by default
	Alias        string   `json:"alias"`         // the functional alias the agent sends as; none when empty
	RequireAlias *bool    `json:"require_alias"` // refuse to send while the alias is not active; true when an alias is set
	Recipients   []string `json:"recipients"`    // MCData IDs the agent may send to, with * wildcards; none when empty
	AliasRetryS  int      `json:"alias_retry_s"` // how often a lost or refused alias is tried again (30 s)
	// allow_senders_hours: the agent may also send to a user who wrote to it within this many hours (0: off),
	// so that a chat agent answers whoever writes to it without a list of the whole fleet.
	AllowSendersHours int `json:"allow_senders_hours"`
	RatePerMin        int `json:"rate_per_minute"`               // all sends together (60)
	RatePerRecipient  int `json:"rate_per_recipient_per_minute"` // sends to one recipient (10)
	// mcp_http: the MCP server over streamable HTTP as well, for an agent in another container (mcphttp.go).
	MCPHTTP      *mcpHTTPConfig `json:"mcp_http"`
	MaxTextBytes int            `json:"max_text_bytes"`
	// voice: MCPTT for a voice agent (voice.go): the talk bursts it hears, the speech it sends.
	Voice voiceConfig `json:"voice"`
	// video: MCVideo for an agent that shows its camera (video.go): the pictures it streams, transmitted in calls.
	Video videoConfig `json:"video"`
}

// voiceConfig is the configuration's "voice".
type voiceConfig struct {
	Enabled    bool `json:"enabled"`
	MaxSpeechS int  `json:"max_speech_s"` // the longest speech the agent may send (60 s)
	Keep       int  `json:"keep"`         // received talk bursts kept, the oldest removed (200)
}

// videoConfig is the configuration's "video": the pictures' size and rate (I420), the encoder's bit rate.
type videoConfig struct {
	Enabled   bool `json:"enabled"`
	Width     int  `json:"width"`      // 320
	Height    int  `json:"height"`     // 240
	FrameRate int  `json:"frame_rate"` // 15
	Bitrate   int  `json:"bitrate"`    // 300000 bit/s
	// auto_transmit: ask for the permission to transmit as soon as the agent is in an MCVideo call.
	AutoTransmit bool `json:"auto_transmit"`
}

// frameBytes is the size of one I420 picture.
func (v videoConfig) frameBytes() int { return v.Width * v.Height * 3 / 2 }

const hardTextLimit = 3000 // well inside one control FIFO line

func loadConfig(file string) (*config, error) {
	raw, err := os.ReadFile(file)
	if err != nil {
		return nil, err
	}
	c := &config{}
	if err := json.Unmarshal(raw, c); err != nil {
		return nil, fmt.Errorf("%s: %w", file, err)
	}
	if c.Engine.Path == "" {
		c.Engine.Path = "/usr/local/bin/mcx-client"
	}
	if c.Engine.Config == "" || c.DataDir == "" {
		return nil, errors.New("the configuration needs engine.config and data_dir")
	}
	if c.OutboxDir == "" {
		c.OutboxDir = filepath.Join(c.DataDir, "outbox")
	}
	if c.RequireAlias == nil {
		need := c.Alias != ""
		c.RequireAlias = &need
	}
	if c.Alias != "" && !validURI(c.Alias) {
		return nil, fmt.Errorf("alias %q is not a SIP URI", c.Alias)
	}
	for _, pattern := range c.Recipients {
		if _, err := path.Match(pattern, ""); err != nil {
			return nil, fmt.Errorf("recipient pattern %q: %w", pattern, err)
		}
	}
	if c.AliasRetryS <= 0 {
		c.AliasRetryS = 30
	}
	if c.RatePerMin <= 0 {
		c.RatePerMin = 60
	}
	if c.RatePerRecipient <= 0 {
		c.RatePerRecipient = 10
	}
	if c.MaxTextBytes <= 0 || c.MaxTextBytes > hardTextLimit {
		c.MaxTextBytes = 1000
	}
	if c.Voice.MaxSpeechS <= 0 || c.Voice.MaxSpeechS > maxSpeechS {
		c.Voice.MaxSpeechS = 60
	}
	if c.Voice.Keep <= 0 {
		c.Voice.Keep = 200
	}
	v := &c.Video
	v.Width, v.Height, v.FrameRate, v.Bitrate = or(v.Width, 320), or(v.Height, 240), or(v.FrameRate, 15), or(v.Bitrate, 300000)
	if v.Width%2 != 0 || v.Height%2 != 0 || v.Width > 1280 || v.Height > 720 || v.FrameRate > 30 {
		return nil, errors.New("video: the picture size is even, at most 1280 x 720, frame_rate at most 30")
	}
	return c, nil
}

// or is v, or def when v is not set.
func or(v, def int) int {
	if v <= 0 {
		return def
	}
	return v
}

// MCData IDs and functional aliases are SIP URIs: one word, so they cannot add an option to
// the engine's command line, and starting with sip:, so they cannot be taken for one.
var uriPattern = regexp.MustCompile(`^sips?:[A-Za-z0-9._~%+-]+@[A-Za-z0-9.-]+(:[0-9]{1,5})?$`)

func validURI(s string) bool { return len(s) <= 256 && uriPattern.MatchString(s) }

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func validMessageID(s string) bool { return uuidPattern.MatchString(s) }

// allowed reports whether the agent may send to id.
func (c *config) allowed(id string) bool {
	for _, pattern := range c.Recipients {
		if ok, _ := path.Match(pattern, id); ok {
			return true
		}
	}
	return false
}

// lineBreaks makes every line break LF.
var lineBreaks = strings.NewReplacer("\r\n", "\n", "\r", "\n", "\u2028", "\n", "\u2029", "\n")

// checkText returns the text as it will be sent: printable UTF-8 with line breaks (LF) and
// tabs, trimmed, at most max_text_bytes.
func (c *config) checkText(text string) (string, error) {
	text = strings.TrimSpace(lineBreaks.Replace(text))
	switch {
	case text == "":
		return "", fail(codeInvalidArgument, "the text is empty")
	case !utf8.ValidString(text):
		return "", fail(codeInvalidArgument, "the text is not UTF-8")
	case len(text) > c.MaxTextBytes:
		return "", fail(codeInvalidArgument, "the text is %d bytes, more than %d", len(text), c.MaxTextBytes)
	}
	for _, r := range text {
		if unicode.IsControl(r) && r != '\t' && r != '\n' {
			return "", fail(codeInvalidArgument, "the text has a control character")
		}
	}
	return text, nil
}

var fileNamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)

// checkFile returns the absolute path of a regular file in the outbox. A relative name is
// taken in the outbox; the engine reads the path as one word, so it has no spaces.
func (c *config) checkFile(name string) (string, error) {
	if !filepath.IsAbs(name) {
		name = filepath.Join(c.OutboxDir, name)
	}
	if !fileNamePattern.MatchString(filepath.Base(name)) {
		return "", fail(codeInvalidArgument, "the file name may hold only letters, digits, '.', '_' and '-'")
	}
	outbox, err := filepath.EvalSymlinks(c.OutboxDir)
	if err != nil {
		return "", fail(codeInternal, "outbox: %v", err)
	}
	real, err := filepath.EvalSymlinks(name)
	if err != nil {
		return "", fail(codeNotFound, "%s is not in the outbox", filepath.Base(name))
	}
	if filepath.Dir(real) != outbox {
		return "", fail(codeInvalidArgument, "only files directly in %s can be sent", c.OutboxDir)
	}
	info, err := os.Stat(real)
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() {
		return "", fail(codeInvalidArgument, "not a regular file")
	}
	return real, nil
}

// limiter allows at most total sends, and perRecipient sends to one recipient, in any minute.
type limiter struct {
	mu                  sync.Mutex
	total, perRecipient int
	sent                []sendRecord
}

type sendRecord struct {
	at time.Time
	to string
}

func (l *limiter) take(to string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	kept, toThem := l.sent[:0], 0
	for _, s := range l.sent {
		if now.Sub(s.at) < time.Minute {
			kept = append(kept, s)
			if s.to == to {
				toThem++
			}
		}
	}
	l.sent = kept
	if len(l.sent) >= l.total {
		return fail(codeRateLimited, "rate limit: %d sends a minute", l.total)
	}
	if toThem >= l.perRecipient {
		return fail(codeRateLimited, "rate limit: %d sends a minute to %s", l.perRecipient, to)
	}
	l.sent = append(l.sent, sendRecord{now, to})
	return nil
}

// auditLog appends one JSON line per tool call.
type auditLog struct {
	mu   sync.Mutex
	file *os.File
}

func openAudit(file string) (*auditLog, error) {
	f, err := os.OpenFile(file, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, err
	}
	return &auditLog{file: f}, nil
}

func (a *auditLog) record(tool string, args map[string]any, result any, err error) {
	// Audio is not logged, only its size.
	logged := map[string]any{}
	for name, v := range args {
		if s, ok := v.(string); ok && len(s) > 512 {
			v = fmt.Sprintf("<%d bytes>", len(s))
		}
		logged[name] = v
	}
	entry := map[string]any{"time": time.Now().UTC().Format(time.RFC3339Nano), "tool": tool, "args": logged}
	if err != nil {
		entry["error"] = err.Error()
	} else {
		entry["result"] = result
	}
	line, _ := json.Marshal(entry)
	a.mu.Lock()
	defer a.mu.Unlock()
	_, _ = a.file.Write(append(line, '\n'))
}
