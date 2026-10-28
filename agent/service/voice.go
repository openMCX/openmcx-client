//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
)

// Voice: MCPTT for a voice agent. A push-to-talk call is a turn-taking conversation, so the agent works in talk
// bursts: the engine writes the speech of each burst it receives as a WAV file (media.speech_dir) and reports it
// (speech_received); the daemon keeps it with the talker's identity, and wait_speech hands it to the agent. The agent
// answers with speak: a WAV the engine sends as a talk burst once the floor is granted (TS 24.380 clause 6.2.4.3.5),
// releasing the floor at its end. The agent makes no calls: calls to it are answered at once (private calls), and
// the group calls of the groups it is affiliated to reach it as to any member.

const (
	speechRate = 16000 // the engine's speech: 16 kHz, 16-bit, mono (AMR-WB)
	maxSpeechS = 300
)

// newID is a random UUID (version 4).
func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6], b[8] = b[6]&0x0f|0x40, b[8]&0x3f|0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// absorbSpeech moves a talk burst the engine wrote into <data_dir>/speech and keeps it in the store.
func (d *daemon) absorbSpeech(fields map[string]any) {
	file := str(fields["file"])
	if filepath.Dir(filepath.Clean(file)) != filepath.Clean(d.speechDir) {
		log.Printf("speech: %q is not in %s", file, d.speechDir)
		return
	}
	id := newID()
	dest := filepath.Join(d.cfg.DataDir, "speech", id+".wav")
	if err := os.Rename(file, dest); err != nil {
		log.Printf("speech: %v", err)
		return
	}
	item := &speech{SpeechID: id, From: str(fields["from"]), CallType: str(fields["call_type"]),
		Group: str(fields["group"]), Peer: str(fields["peer"]), DurationMS: max(integer(fields["duration_ms"]), 0),
		Received: time.Now(), File: dest}
	if item.CallType != "private" {
		item.Peer = ""
	} else {
		item.Group = ""
	}
	err := d.store.update(func() bool {
		item.Seq = d.store.NextSpeech
		d.store.NextSpeech++
		d.store.Speech = append(d.store.Speech, item)
		return true
	})
	if err != nil {
		log.Printf("store: %v", err)
	}
}

// ---- WAV ----

// readWAV is the speech of a PCM WAV file (16-bit, mono or stereo, any rate) as 16 kHz mono samples.
func readWAV(raw []byte) ([]int16, error) {
	if len(raw) < 12 || string(raw[0:4]) != "RIFF" || string(raw[8:12]) != "WAVE" {
		return nil, errors.New("not a WAV file (RIFF/WAVE)")
	}
	var channels, bits, format int
	var rate int
	var data []byte
	for at := 12; at+8 <= len(raw); {
		id, size := string(raw[at:at+4]), int(binary.LittleEndian.Uint32(raw[at+4:at+8]))
		body := raw[at+8 : min(at+8+size, len(raw))]
		switch id {
		case "fmt ":
			if len(body) < 16 {
				return nil, errors.New("WAV: short fmt chunk")
			}
			format = int(binary.LittleEndian.Uint16(body[0:2]))
			channels = int(binary.LittleEndian.Uint16(body[2:4]))
			rate = int(binary.LittleEndian.Uint32(body[4:8]))
			bits = int(binary.LittleEndian.Uint16(body[14:16]))
			if format == 0xFFFE && len(body) >= 26 { // WAVE_FORMAT_EXTENSIBLE: the sub-format's first two bytes
				format = int(binary.LittleEndian.Uint16(body[24:26]))
			}
		case "data":
			data = body
		}
		at += 8 + size + size%2
	}
	switch {
	case format != 1 || bits != 16:
		return nil, errors.New("WAV: only 16-bit PCM")
	case channels < 1 || channels > 2:
		return nil, errors.New("WAV: mono or stereo only")
	case rate < 8000 || rate > 48000:
		return nil, errors.New("WAV: the sample rate is 8000 to 48000 Hz")
	case data == nil:
		return nil, errors.New("WAV: no data chunk")
	}
	frames := len(data) / (2 * channels)
	mono := make([]float64, frames)
	for i := range mono {
		sum := 0.0
		for c := range channels {
			sum += float64(int16(binary.LittleEndian.Uint16(data[(i*channels+c)*2:])))
		}
		mono[i] = sum / float64(channels)
	}
	return resample(mono, rate), nil
}

// resample turns samples at rate into 16 kHz by linear interpolation.
func resample(in []float64, rate int) []int16 {
	if len(in) == 0 {
		return nil
	}
	n := int(int64(len(in)) * speechRate / int64(rate))
	out := make([]int16, n)
	for i := range out {
		pos := float64(i) * float64(rate) / speechRate
		j := int(pos)
		v := in[min(j, len(in)-1)]
		if j+1 < len(in) {
			v += (in[j+1] - v) * (pos - float64(j))
		}
		out[i] = int16(max(min(v, 32767), -32768))
	}
	return out
}

// writeWAV is 16 kHz mono 16-bit PCM as a WAV file.
func writeWAV(samples []int16) []byte {
	out := make([]byte, 44+2*len(samples))
	copy(out[0:], "RIFF")
	binary.LittleEndian.PutUint32(out[4:], uint32(36+2*len(samples)))
	copy(out[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(out[16:], 16)
	binary.LittleEndian.PutUint16(out[20:], 1) // PCM
	binary.LittleEndian.PutUint16(out[22:], 1) // mono
	binary.LittleEndian.PutUint32(out[24:], speechRate)
	binary.LittleEndian.PutUint32(out[28:], speechRate*2)
	binary.LittleEndian.PutUint16(out[32:], 2)
	binary.LittleEndian.PutUint16(out[34:], 16)
	copy(out[36:], "data")
	binary.LittleEndian.PutUint32(out[40:], uint32(2*len(samples)))
	for i, v := range samples {
		binary.LittleEndian.PutUint16(out[44+2*i:], uint16(v))
	}
	return out
}

// ---- Tools ----

func (d *daemon) toolCallStatus(args) (any, error) {
	st := d.latest()
	out := map[string]any{
		"mcptt_id":       d.eng.mcpttID,
		"state":          str(st["call_state"]), // idle, calling, active
		"selected_group": str(st["group_id"]),
		"affiliated":     st["affiliated"] == true,
		"floor_control":  st["floor_control"] == true,
		"floor_owner":    str(st["floor_owner"]),
		"speaking":       str(st["floor_state"]) == "HasPermission",
		"queued":         st["queued"] == true,
	}
	if out["state"] == "" {
		out["state"] = "idle"
	}
	if out["state"] != "idle" {
		out["type"] = str(st["call_type"])
		if str(st["call_type"]) == "private" {
			out["peer"] = str(st["private_peer"])
		} else if out["group"] = str(st["call_group"]); out["group"] == "" {
			out["group"] = str(st["group_id"]) // a call on the selected group
		}
	}
	d.store.view(func() { out["last_speech_seq"] = d.store.NextSpeech - 1 })
	return out, nil
}

// speechResult is a talk burst as wait_speech returns it, with its audio as base64 WAV when asked for.
func speechResult(item speech, audio bool) (map[string]any, error) {
	out := map[string]any{"seq": item.Seq, "speech_id": item.SpeechID, "from": item.From, "call_type": item.CallType,
		"duration_ms": item.DurationMS, "received": item.Received, "file": item.File}
	if item.Group != "" {
		out["group"] = item.Group
	}
	if item.Peer != "" {
		out["peer"] = item.Peer
	}
	if audio {
		raw, err := os.ReadFile(item.File)
		if err != nil {
			return nil, fail(codeNotFound, "the audio of %s is gone: %v", item.SpeechID, err)
		}
		out["audio"] = map[string]any{"mime_type": "audio/wav", "sample_rate": speechRate, "channels": 1,
			"base64": base64.StdEncoding.EncodeToString(raw)}
	}
	return out, nil
}

func (d *daemon) toolWaitSpeech(a args) (any, error) {
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	var since uint64
	d.store.view(func() { since = d.store.NextSpeech - 1 })
	if a.has("since") {
		since = uint64(max(a.i("since"), 0))
	}
	var found *speech
	err = d.changed.waitUntil(limit, func() (bool, error) {
		d.store.view(func() {
			for _, item := range d.store.Speech {
				if item.Seq > since && (a.s("from") == "" || item.From == a.s("from")) {
					copied := *item
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
	item, err := speechResult(*found, a.s("audio") == "inline")
	if err != nil {
		return nil, err
	}
	return map[string]any{"timed_out": false, "item": item, "last_seq": found.Seq}, nil
}

// speechSamples is the speech a speak call gives: base64 WAV, or a WAV file in the outbox.
func (d *daemon) speechSamples(a args) ([]int16, error) {
	var raw []byte
	switch {
	case a.s("audio_base64") != "" && a.s("file") != "":
		return nil, fail(codeInvalidArgument, "give audio_base64 or file, not both")
	case a.s("audio_base64") != "":
		decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(a.s("audio_base64")))
		if err != nil {
			return nil, fail(codeInvalidArgument, "audio_base64 is not base64: %v", err)
		}
		raw = decoded
	case a.s("file") != "":
		file, err := d.cfg.checkFile(a.s("file"))
		if err != nil {
			return nil, err
		}
		if raw, err = os.ReadFile(file); err != nil {
			return nil, fail(codeNotFound, "%v", err)
		}
	default:
		return nil, fail(codeInvalidArgument, "speak needs audio_base64 or file")
	}
	samples, err := readWAV(raw)
	if err != nil {
		return nil, fail(codeInvalidArgument, "%v", err)
	}
	if len(samples) < speechRate/10 {
		return nil, fail(codeInvalidArgument, "the speech is shorter than 0.1 s")
	}
	if len(samples) > d.cfg.Voice.MaxSpeechS*speechRate {
		return nil, fail(codeInvalidArgument, "the speech is %.1f s, more than max_speech_s (%d)",
			float64(len(samples))/speechRate, d.cfg.Voice.MaxSpeechS)
	}
	return samples, nil
}

// findEvent is the first of the kinds among events, or "".
func findEvent(events []engineEvent, kinds ...string) string {
	for _, ev := range events {
		for _, kind := range kinds {
			if ev.Kind == kind {
				return kind
			}
		}
	}
	return ""
}

// firstKind is the first of the kinds, in their order, that is among events; "" when none is.
func firstKind(events []engineEvent, kinds ...string) string {
	for _, kind := range kinds {
		if slices.ContainsFunc(events, func(ev engineEvent) bool { return ev.Kind == kind }) {
			return kind
		}
	}
	return ""
}

func (d *daemon) toolSpeak(a args) (any, error) {
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	samples, err := d.speechSamples(a)
	if err != nil {
		return nil, err
	}
	if !d.speakMu.TryLock() {
		return nil, fail(codeBusy, "the agent is already speaking")
	}
	defer d.speakMu.Unlock()
	if str(d.latest()["call_state"]) != "active" {
		return nil, fail(codeNoCall, "the agent is in no MCPTT call")
	}
	duration := time.Duration(len(samples)) * time.Second / speechRate
	file := filepath.Join(d.cfg.DataDir, "speech-out", newID()+".wav")
	if err := writeAtomic(file, writeWAV(samples)); err != nil {
		return nil, err
	}
	defer os.Remove(file)
	out := map[string]any{"duration_ms": duration.Milliseconds(), "floor_control": d.latest()["floor_control"] == true}

	// Wait for another talker to finish first: a request while the floor is taken is denied (or queued).
	if a.b("wait_idle") && out["floor_control"] == true {
		err := d.changed.waitUntil(limit, func() (bool, error) {
			st := d.latest()
			return str(st["floor_owner"]) == "" || str(st["call_state"]) != "active", nil
		})
		if errors.Is(err, errTimeout) {
			out["outcome"], out["spoken"] = "floor_busy", false
			return out, nil
		}
	}
	// The command takes the command lock only until the engine has it; the grant is awaited without it, so that a
	// queued request does not hold back the agent's messages.
	from, started := d.journal.last(), time.Now()
	grantKinds := []string{"floor_granted", "speech_started", "queued_floor_granted", "floor_denied", "call_disconnected"}
	_, err = d.transact("ptt "+file, 500*time.Millisecond, func(events []engineEvent, _ map[string]any) (any, bool, error) {
		return nil, findEvent(events, grantKinds...) != "", nil // no client_error and no answer yet: still fine
	})
	if err != nil && !errors.Is(err, errTimeout) {
		return nil, err
	}
	var outcome string
	var answer engineEvent
	err = d.changed.waitUntil(limit, func() (bool, error) {
		for _, ev := range d.journal.since(from) {
			if slices.Contains(grantKinds, ev.Kind) {
				outcome, answer = ev.Kind, ev
				return true, nil
			}
		}
		return false, nil
	})
	if errors.Is(err, errTimeout) {
		_ = d.fire("release") // the request still pending, or queued
		out["outcome"], out["spoken"] = "no_grant", false
		return out, nil
	}
	switch outcome {
	case "queued_floor_granted":
		// A queued request granted: confirmed at once (TS 24.380 clause 6.2.4.6.3).
		if err := d.fire("floor-confirm"); err != nil {
			return nil, err
		}
	case "floor_denied":
		out["outcome"], out["spoken"] = "denied", false
		if cause := integer(answer.Fields["reject_cause"]); cause >= 0 {
			out["reject_cause"] = cause
		}
		return out, nil
	case "call_disconnected":
		out["outcome"], out["spoken"] = "call_ended", false
		return out, nil
	}
	if a.s("wait") == "granted" {
		out["outcome"], out["spoken"] = "granted", false
		return out, nil
	}
	// The end of the burst; a revocation or loss releases the floor first (floor_released, then floor_revoked).
	endKinds := []string{"floor_revoked", "floor_lost", "call_disconnected", "floor_released"}
	var ended string
	_ = d.changed.waitUntil(duration+10*time.Second, func() (bool, error) {
		ended = firstKind(d.journal.since(from), endKinds...)
		return ended != "", nil
	})
	if ended == "floor_released" {
		time.Sleep(100 * time.Millisecond)
		ended = firstKind(d.journal.since(from), endKinds...)
	}
	elapsed := time.Since(started)
	switch ended {
	case "floor_released":
		out["outcome"] = "spoken"
		if elapsed+300*time.Millisecond < duration {
			out["outcome"] = "stopped" // stop_speaking
		}
	case "floor_revoked":
		out["outcome"] = "revoked" // a talker of higher priority took the floor
	case "floor_lost":
		out["outcome"] = "lost"
	case "call_disconnected":
		out["outcome"] = "call_ended"
	default:
		out["outcome"] = "timeout"
	}
	out["spoken"] = out["outcome"] == "spoken"
	out["spoken_ms"] = min(elapsed, duration).Milliseconds()
	return out, nil
}

func (d *daemon) toolStopSpeaking(args) (any, error) {
	if str(d.latest()["call_state"]) != "active" {
		return nil, fail(codeNoCall, "the agent is in no MCPTT call")
	}
	if err := d.fire("release"); err != nil {
		return nil, err
	}
	return map[string]any{"released": true}, nil
}

func (d *daemon) toolHangup(args) (any, error) {
	if state := str(d.latest()["call_state"]); state == "" || state == "idle" {
		return nil, fail(codeNoCall, "the agent is in no MCPTT call")
	}
	_, err := d.transact("hangup", 10*time.Second, func(events []engineEvent, st map[string]any) (any, bool, error) {
		return nil, str(st["call_state"]) == "idle", nil
	})
	if err != nil {
		return nil, err
	}
	return map[string]any{"state": "idle"}, nil
}
