//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"sync"
	"syscall"
	"time"
)

// Video: MCVideo for an agent that shows what its camera sees (a drone, a robot). The agent streams its pictures to
// the daemon as raw I420 (POST /video, one picture after another, each video.width x video.height luma bytes and
// the two quarter-size chroma planes); the daemon writes them to the engine's FIFO (mcvideo.live), and the engine
// transmits the latest picture at each frame while it has the permission to transmit (TS 24.581 clause 6.2.4.5.2),
// as H.264 (TS 26.281 clause 4.1.1). The agent makes no calls: it is called, or joins its groups' calls, and the
// tools ask for that permission in them; the video other users transmit is not received.

// videoFeed is the stream of pictures to the engine: one stream at a time.
type videoFeed struct {
	fifo                  string
	frameBytes, frameRate int

	mu        sync.Mutex
	streaming bool
	pictures  uint64    // pictures written to the engine since the daemon started
	last      time.Time // when the last one was written
	streams   int       // streams that ended
	lastError string    // why the last stream ended early

	received *videoReceiver // the video others transmit (video.receive); nil without it
}

// state is the feed as video_status shows it.
func (v *videoFeed) state() map[string]any {
	v.mu.Lock()
	defer v.mu.Unlock()
	out := map[string]any{"streaming": v.streaming, "pictures": v.pictures, "streams_ended": v.streams}
	if !v.last.IsZero() {
		out["last_picture_age_ms"] = time.Since(v.last).Milliseconds()
	}
	if v.lastError != "" {
		out["last_error"] = v.lastError
	}
	return out
}

func writeFeedReply(w http.ResponseWriter, status int, body map[string]any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// serve takes one stream of pictures: POST /video, the body raw I420 pictures one after another; ?pace=true writes
// them at the frame rate (a recorded file), else as they come (a live camera). The answer comes when the stream ends:
// {"ok": true, "pictures": n, "seconds": s}; a picture cut short at the end is dropped.
func (v *videoFeed) serve(w http.ResponseWriter, r *http.Request) {
	if v == nil {
		writeFeedReply(w, http.StatusNotFound, map[string]any{"ok": false, "code": codeNotEnabled,
			"error": "this agent does not use video (video.enabled)"})
		return
	}
	v.mu.Lock()
	if v.streaming {
		v.mu.Unlock()
		writeFeedReply(w, http.StatusConflict, map[string]any{"ok": false, "code": codeBusy,
			"error": "another stream of pictures is going on"})
		return
	}
	v.streaming = true
	v.mu.Unlock()
	pictures, started, err := v.stream(r.Body, r.URL.Query().Get("pace") == "true")
	v.mu.Lock()
	v.streaming = false
	v.streams++
	v.lastError = ""
	if err != nil {
		v.lastError = err.Error()
	}
	v.mu.Unlock()
	seconds := time.Since(started).Seconds()
	if err != nil {
		log.Printf("video: the stream ended after %d pictures: %v", pictures, err)
		writeFeedReply(w, http.StatusServiceUnavailable, map[string]any{"ok": false, "code": errorCode(err),
			"error": err.Error(), "pictures": pictures, "seconds": seconds})
		return
	}
	writeFeedReply(w, http.StatusOK, map[string]any{"ok": true, "pictures": pictures, "seconds": seconds})
}

// stream writes whole pictures of body to the engine's FIFO until the body ends.
func (v *videoFeed) stream(body io.Reader, pace bool) (uint64, time.Time, error) {
	started := time.Now()
	// The engine holds the FIFO open; without it (not running) the open fails at once (ENXIO).
	f, err := os.OpenFile(v.fifo, os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return 0, started, fail(codeNotReady, "the engine does not take pictures: %v", err)
	}
	defer f.Close()
	picture := make([]byte, v.frameBytes)
	interval := time.Second / time.Duration(v.frameRate)
	next := time.Now()
	var count uint64
	for {
		if _, err := io.ReadFull(body, picture); err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return count, started, nil
			}
			return count, started, fail(codeInvalidArgument, "reading the pictures: %v", err)
		}
		_ = f.SetWriteDeadline(time.Now().Add(3 * time.Second))
		if _, err := f.Write(picture); err != nil {
			return count, started, fail(codeNotReady, "the engine stopped taking pictures: %v", err)
		}
		count++
		v.mu.Lock()
		v.pictures++
		v.last = time.Now()
		v.mu.Unlock()
		if pace {
			next = next.Add(interval)
			time.Sleep(time.Until(next))
		}
	}
}

// ---- Tools ----

func (d *daemon) toolVideoStatus(args) (any, error) {
	st := d.latest()
	state := str(st["mcvideo_call_state"])
	if state == "" {
		state = "idle"
	}
	feed := d.video.state()
	feed["format"] = "i420"
	feed["width"], feed["height"], feed["frame_rate"] = d.cfg.Video.Width, d.cfg.Video.Height, d.cfg.Video.FrameRate
	feed["frame_bytes"] = d.cfg.Video.frameBytes()
	if live, ok := st["mcvideo_live"].(map[string]any); ok {
		feed["engine_pictures"] = live["pictures"]
	}
	received := map[string]any{"enabled": d.video.received != nil}
	if r := d.video.received; r != nil {
		pictures, last := r.counts()
		received["pictures"] = pictures
		if !last.IsZero() {
			received["last_picture_age_ms"] = time.Since(last).Milliseconds()
		}
	}
	out := map[string]any{
		"mcvideo_id":     d.eng.mcvideoID,
		"state":          state,
		"selected_group": str(st["mcvideo_selected_group"]),
		"affiliated":     st["mcvideo_affiliated"] == true,
		"transmitting":   st["mcvideo_transmitting"] == true,
		"feed":           feed,
		"received":       received,
	}
	if state != "idle" {
		out["type"] = str(st["mcvideo_call_type"])
		out["video_mode"] = str(st["mcvideo_video_mode"])
		if str(st["mcvideo_call_type"]) == "private" {
			out["peer"] = str(st["mcvideo_peer"])
		} else {
			out["group"] = str(st["mcvideo_group"])
		}
		out["transmitter"] = str(st["mcvideo_transmitter"])
		out["participants"] = st["mcvideo_participants"]
		out["frames_sent"] = st["mcvideo_frames_sent"]
		if q := st["mcvideo_queue_position"]; q != nil {
			out["queue_position"] = q
		}
	}
	return out, nil
}

// awaitTransmission waits after "from" for the outcome of a request for the permission to transmit.
func (d *daemon) awaitTransmission(from uint64, limit time.Duration) (string, error) {
	var outcome string
	err := d.changed.waitUntil(limit, func() (bool, error) {
		if d.latest()["mcvideo_transmitting"] == true {
			outcome = "granted"
			return true, nil
		}
		switch findEvent(d.journal.since(from), "mcvideo_transmission_rejected", "mcvideo_call_ended") {
		case "mcvideo_transmission_rejected":
			outcome = "rejected"
		case "mcvideo_call_ended":
			outcome = "call_ended"
		}
		return outcome != "", nil
	})
	if errors.Is(err, errTimeout) {
		if d.latest()["mcvideo_queue_position"] != nil {
			return "queued", nil
		}
		return "no_answer", nil
	}
	return outcome, err
}

// transmission asks for the permission to transmit in the current call and returns the outcome.
func (d *daemon) transmission(limit time.Duration) (map[string]any, error) {
	if d.latest()["mcvideo_transmitting"] == true {
		return map[string]any{"transmission": "granted"}, nil
	}
	from := d.journal.last()
	if err := d.fire("mcvideo-transmit"); err != nil {
		return nil, err
	}
	outcome, err := d.awaitTransmission(from, limit)
	if err != nil {
		return nil, err
	}
	out := map[string]any{"transmission": outcome}
	if outcome == "rejected" {
		out["reject_cause"] = d.latest()["mcvideo_reject_cause"]
	}
	return out, nil
}

func (d *daemon) videoInCall() error {
	if state := str(d.latest()["mcvideo_call_state"]); state != "active" {
		return fail(codeNoCall, "the agent is in no active MCVideo call")
	}
	return nil
}

func (d *daemon) toolVideoTransmit(a args) (any, error) {
	limit, err := timeout(a)
	if err != nil {
		return nil, err
	}
	if err := d.videoInCall(); err != nil {
		return nil, err
	}
	return d.transmission(limit)
}

func (d *daemon) toolVideoStop(args) (any, error) {
	if err := d.videoInCall(); err != nil {
		return nil, err
	}
	if d.latest()["mcvideo_transmitting"] != true {
		return map[string]any{"transmitting": false}, nil
	}
	if err := d.fire("mcvideo-end"); err != nil {
		return nil, err
	}
	return map[string]any{"transmitting": false}, nil
}

func (d *daemon) toolVideoHangup(args) (any, error) {
	if state := str(d.latest()["mcvideo_call_state"]); state == "" || state == "idle" {
		return nil, fail(codeNoCall, "the agent is in no MCVideo call")
	}
	_, err := d.transact("mcvideo-hangup", 10*time.Second, func(events []engineEvent, st map[string]any) (any, bool, error) {
		return nil, str(st["mcvideo_call_state"]) == "idle", nil
	})
	if err != nil {
		return nil, err
	}
	return map[string]any{"state": "idle"}, nil
}

// autoTransmit (video.auto_transmit) asks for the permission to transmit once the agent is in an MCVideo call.
func (d *daemon) autoTransmit() {
	time.Sleep(300 * time.Millisecond) // the status shows the call
	st := d.latest()
	// The viewer of a push or of an ambient viewing call does not transmit; neither does a granted transmitter.
	if st["mcvideo_transmitting"] == true || str(st["mcvideo_ambient_role"]) == "viewing" ||
		str(st["mcvideo_video_mode"]) == "one-to-one video push" {
		return
	}
	out, err := d.transmission(10 * time.Second)
	log.Printf("video: auto_transmit: %v %v", out, err)
}
