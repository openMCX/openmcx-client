//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

const (
	agentMCPTT = "sip:agent.mcptt@example.org"
	bob        = "sip:bob.mcptt@example.org"
	opsGroup   = "sip:ops@example.org"
)

// startMedia runs a daemon with voice and video on the fake engine; mcdata false leaves MCData out.
func startMedia(t *testing.T, mcdata bool, edit func(*config)) *daemon {
	t.Helper()
	extra := map[string]any{
		"mcptt":   map[string]any{"user_id": agentMCPTT, "group_id": opsGroup},
		"mcvideo": map[string]any{"user_id": "sip:agent.mcvideo@example.org", "group_id": opsGroup},
	}
	if !mcdata {
		extra["mcdata"] = nil
	}
	return startDaemonWith(t, extra, nil, func(c *config) {
		if !mcdata {
			c.Alias = ""
		}
		c.Voice = voiceConfig{Enabled: true, MaxSpeechS: 10, Keep: 3}
		c.Video = videoConfig{Enabled: true, Width: 32, Height: 16, FrameRate: 15, Bitrate: 100000}
		if edit != nil {
			edit(c)
		}
	})
}

// wav is a 16-bit PCM WAV of a 440 Hz-ish square wave.
func wav(rate, channels int, seconds float64) []byte {
	frames := int(float64(rate) * seconds)
	data := make([]byte, frames*channels*2)
	for i := range frames {
		v := int16(8000)
		if i/(rate/880+1)%2 == 0 {
			v = -8000
		}
		for c := range channels {
			binary.LittleEndian.PutUint16(data[(i*channels+c)*2:], uint16(v))
		}
	}
	var b bytes.Buffer
	b.WriteString("RIFF")
	_ = binary.Write(&b, binary.LittleEndian, uint32(36+len(data)))
	b.WriteString("WAVEfmt ")
	for _, field := range []any{uint32(16), uint16(1), uint16(channels), uint32(rate), uint32(rate * channels * 2),
		uint16(channels * 2), uint16(16)} {
		_ = binary.Write(&b, binary.LittleEndian, field)
	}
	b.WriteString("data")
	_ = binary.Write(&b, binary.LittleEndian, uint32(len(data)))
	b.Write(data)
	return b.Bytes()
}

func TestReadWAVResamples(t *testing.T) {
	for _, c := range []struct{ rate, channels int }{{16000, 1}, {8000, 1}, {48000, 2}, {22050, 2}} {
		samples, err := readWAV(wav(c.rate, c.channels, 0.5))
		if err != nil {
			t.Fatal(err)
		}
		if len(samples) < 7990 || len(samples) > 8000 {
			t.Fatalf("%d Hz x%d: %d samples, want 8000", c.rate, c.channels, len(samples))
		}
	}
	back, err := readWAV(writeWAV([]int16{1, -2, 3, 32767, -32768}))
	if err != nil || len(back) != 5 || back[3] != 32767 || back[4] != -32768 {
		t.Fatalf("round trip %v %v", back, err)
	}
	for _, bad := range [][]byte{[]byte("not a wav"), wav(16000, 3, 0.1), wav(4000, 1, 0.1)} {
		if _, err := readWAV(bad); err == nil {
			t.Fatalf("accepted a bad WAV")
		}
	}
}

func TestWaitSpeech(t *testing.T) {
	d := startMedia(t, true, nil)
	callIn(t, d, "fake-call-in group", "call_state")
	_ = d.eng.command("fake-speech " + bob + " 700")
	out := call(t, d, "wait_speech", map[string]any{"since": 0.0, "timeout_s": 5.0})
	item := out["item"].(map[string]any)
	if item["from"] != bob || item["group"] != opsGroup || item["call_type"] != "prearranged" || item["duration_ms"] != 700.0 {
		t.Fatalf("speech %v", item)
	}
	audio := item["audio"].(map[string]any)
	raw, _ := base64.StdEncoding.DecodeString(audio["base64"].(string))
	samples, err := readWAV(raw)
	if err != nil || len(samples) != 700*16 {
		t.Fatalf("audio: %d samples, %v", len(samples), err)
	}
	if filepath.Dir(item["file"].(string)) != filepath.Join(d.cfg.DataDir, "speech") {
		t.Fatalf("file %v", item["file"])
	}
	// The cursor: nothing after it; a filter on the talker; audio left out.
	timedOut := call(t, d, "wait_speech", map[string]any{"since": out["last_seq"], "timeout_s": 1.0})
	if timedOut["timed_out"] != true {
		t.Fatalf("wait %v", timedOut)
	}
	_ = d.eng.command("fake-speech sip:carol.mcptt@example.org 300")
	_ = d.eng.command("fake-speech " + bob + " 400")
	next := call(t, d, "wait_speech", map[string]any{"since": out["last_seq"], "from": bob, "audio": "none", "timeout_s": 5.0})
	if it := next["item"].(map[string]any); it["duration_ms"] != 400.0 || it["audio"] != nil {
		t.Fatalf("filtered %v", it)
	}
	// keep 3: the oldest burst and its file are gone.
	_ = d.eng.command("fake-speech " + bob + " 200")
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		var n, last int
		d.store.view(func() { n, last = len(d.store.Speech), d.store.Speech[len(d.store.Speech)-1].DurationMS })
		return n == 3 && last == 200, nil
	}); err != nil {
		t.Fatal("the store keeps more than 3 bursts")
	}
	if _, err := os.Stat(item["file"].(string)); !os.IsNotExist(err) {
		t.Fatalf("the oldest burst's file is still there: %v", err)
	}
}

func TestSpeak(t *testing.T) {
	d := startMedia(t, true, nil)
	audio := base64.StdEncoding.EncodeToString(wav(8000, 2, 0.6))
	callCode(t, d, "speak", map[string]any{"audio_base64": audio}, codeNoCall, "no MCPTT call")
	callIn(t, d, "fake-call-in private "+bob, "call_state")
	st := call(t, d, "call_status", nil)
	if st["state"] != "active" || st["type"] != "private" || st["peer"] != bob {
		t.Fatalf("call %v", st)
	}
	out := call(t, d, "speak", map[string]any{"audio_base64": audio})
	if out["outcome"] != "spoken" || out["spoken"] != true || out["duration_ms"] != 600.0 {
		t.Fatalf("speak %v", out)
	}
	if left, _ := os.ReadDir(filepath.Join(d.cfg.DataDir, "speech-out")); len(left) != 0 {
		t.Fatalf("speech-out keeps %v", left)
	}

	// A file from the outbox; returning at the grant, then stopped.
	if err := os.WriteFile(filepath.Join(d.cfg.OutboxDir, "hello.wav"), wav(16000, 1, 3), 0o600); err != nil {
		t.Fatal(err)
	}
	if out := call(t, d, "speak", map[string]any{"file": "hello.wav", "wait": "granted"}); out["outcome"] != "granted" {
		t.Fatalf("granted %v", out)
	}
	call(t, d, "stop_speaking", nil)

	// Denied; revoked; another user holding the floor.
	_ = d.eng.command("fake-floor deny")
	if out := call(t, d, "speak", map[string]any{"audio_base64": audio}); out["outcome"] != "denied" || out["reject_cause"] != 1.0 {
		t.Fatalf("denied %v", out)
	}
	_ = d.eng.command("fake-floor grant")
	go func() {
		time.Sleep(200 * time.Millisecond)
		_ = d.eng.command("fake-revoke")
	}()
	long := base64.StdEncoding.EncodeToString(wav(16000, 1, 2))
	if out := call(t, d, "speak", map[string]any{"audio_base64": long}); out["outcome"] != "revoked" {
		t.Fatalf("revoked %v", out)
	}
	_ = d.eng.command("fake-floor-owner " + bob)
	time.Sleep(300 * time.Millisecond)
	if out := call(t, d, "speak", map[string]any{"audio_base64": audio, "timeout_s": 1.0}); out["outcome"] != "floor_busy" {
		t.Fatalf("busy %v", out)
	}
	_ = d.eng.command("fake-floor-owner none")

	// Too long, not a WAV, both sources.
	callCode(t, d, "speak", map[string]any{"audio_base64": base64.StdEncoding.EncodeToString(wav(16000, 1, 11))},
		codeInvalidArgument, "max_speech_s")
	callCode(t, d, "speak", map[string]any{"audio_base64": "aGVsbG8="}, codeInvalidArgument, "WAV")
	callCode(t, d, "speak", map[string]any{"audio_base64": audio, "file": "hello.wav"}, codeInvalidArgument, "not both")

	call(t, d, "hangup", nil)
	if st := call(t, d, "call_status", nil); st["state"] != "idle" {
		t.Fatalf("after hangup %v", st)
	}
	callCode(t, d, "hangup", nil, codeNoCall, "no MCPTT call")
}

// callIn has the fake engine answer a call to the agent, and waits for the status to show it (key: call_state or
// mcvideo_call_state).
func callIn(t *testing.T, d *daemon, line, key string) {
	t.Helper()
	_ = d.eng.command(line)
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		return str(d.latest()[key]) == "active", nil
	}); err != nil {
		t.Fatalf("%s: no call", line)
	}
}

// The agent makes no calls of its own: no tool starts one.
func TestNoOutgoingCalls(t *testing.T) {
	d := startMedia(t, true, nil)
	for _, name := range []string{"start_call", "video_call"} {
		callCode(t, d, name, map[string]any{"kind": "private", "to": bob}, codeUnknownTool, "unknown tool")
	}
	for _, tl := range tools {
		for _, p := range tl.params {
			if p.name == "to" && tl.service != "mcdata" {
				t.Fatalf("%s takes a callee (%q)", tl.name, p.name)
			}
		}
	}
}

func TestServicesEnabled(t *testing.T) {
	d := startMedia(t, false, nil)
	callCode(t, d, "send_message", map[string]any{"to": alice, "text": "hi"}, codeNotEnabled, "MCData")
	st := call(t, d, "status", nil)
	svc := st["services"].(map[string]any)
	if svc["mcdata"].(map[string]any)["enabled"] != false || svc["mcptt"].(map[string]any)["ready"] != true ||
		svc["mcvideo"].(map[string]any)["ready"] != true || st["ready"] != true || st["can_send"] != false {
		t.Fatalf("status %v", st)
	}
	// MCData only: voice and video are not enabled.
	m := startDaemon(t, nil, nil)
	callCode(t, m, "speak", map[string]any{"audio_base64": "x"}, codeNotEnabled, "voice")
	callCode(t, m, "video_status", nil, codeNotEnabled, "video")
	if exitStatus(codeNotEnabled) != 3 {
		t.Fatal("not_enabled is a policy refusal")
	}
}

func TestVideo(t *testing.T) {
	d := startMedia(t, true, nil)
	server := httptest.NewServer(d.handler())
	defer server.Close()
	size := d.cfg.Video.frameBytes()
	pictures := bytes.Repeat([]byte{0x80}, 5*size+size/2) // the half picture at the end is dropped
	resp, err := http.Post(server.URL+"/video?pace=true", "application/octet-stream", bytes.NewReader(pictures))
	if err != nil {
		t.Fatal(err)
	}
	var reply map[string]any
	decode(t, resp, &reply)
	if reply["ok"] != true || reply["pictures"] != 5.0 || reply["seconds"].(float64) < 0.25 {
		t.Fatalf("feed %v", reply)
	}
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		feed := call(t, d, "video_status", nil)["feed"].(map[string]any)
		return feed["engine_pictures"] == 5.0 && feed["pictures"] == 5.0, nil
	}); err != nil {
		t.Fatalf("the engine did not get 5 pictures: %v", call(t, d, "video_status", nil))
	}

	callCode(t, d, "video_transmit", nil, codeNoCall, "no active MCVideo call")
	callIn(t, d, "fake-video-in group", "mcvideo_call_state")
	if out := call(t, d, "video_transmit", nil); out["transmission"] != "granted" {
		t.Fatalf("group call %v", out)
	}
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		return d.latest()["mcvideo_transmitting"] == true, nil
	}); err != nil {
		t.Fatal(err)
	}
	st := call(t, d, "video_status", nil)
	if st["state"] != "active" || st["transmitting"] != true || st["group"] != opsGroup {
		t.Fatalf("status %v", st)
	}
	call(t, d, "video_stop", nil)
	if out := call(t, d, "video_transmit", nil); out["transmission"] != "granted" {
		t.Fatalf("transmit %v", out)
	}
	call(t, d, "video_hangup", nil)
	callCode(t, d, "video_hangup", nil, codeNoCall, "no MCVideo call")
}

func TestVideoAutoTransmit(t *testing.T) {
	d := startMedia(t, true, func(c *config) { c.Video.AutoTransmit = true })
	callIn(t, d, "fake-video-in private sip:bob.mcvideo@example.org", "mcvideo_call_state")
	if err := d.changed.waitUntil(5*time.Second, func() (bool, error) {
		return call(t, d, "video_status", nil)["transmitting"] == true, nil
	}); err != nil {
		t.Fatal("auto_transmit did not ask for the permission to transmit")
	}
}

func decode(t *testing.T, resp *http.Response, v any) {
	t.Helper()
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(v); err != nil {
		t.Fatal(err)
	}
}
