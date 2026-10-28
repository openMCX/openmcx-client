//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"log"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Received video, for an agent that watches (video.receive): the engine sends each H.264 picture it receives in an
// MCVideo call to a loopback UDP port (mcvideo.live_receive: a byte 1 for an IDR picture, the RTP timestamp, 4 bytes,
// then the NAL units in annex B), and GET /video/received streams them as an H.264 elementary stream (annex B),
// starting at the next IDR picture, which ffmpeg and the like read as is (-f h264).

// videoReceiver fans the engine's pictures out to the streams open on GET /video/received.
type videoReceiver struct {
	conn *net.UDPConn

	mu       sync.Mutex
	clients  map[chan []byte]struct{}
	pictures uint64
	last     time.Time
}

func newVideoReceiver(listen string) (*videoReceiver, error) {
	addr, err := net.ResolveUDPAddr("udp", listen)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return nil, err
	}
	_ = conn.SetReadBuffer(4 << 20)
	r := &videoReceiver{conn: conn, clients: map[chan []byte]struct{}{}}
	go r.receive()
	return r, nil
}

func (r *videoReceiver) receive() {
	buf := make([]byte, 65536)
	for {
		n, _, err := r.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("received video: %v", err)
			return
		}
		if n < 6 {
			continue
		}
		picture := append([]byte(nil), buf[:n]...)
		r.mu.Lock()
		r.pictures++
		r.last = time.Now()
		for c := range r.clients {
			select {
			case c <- picture:
			default: // a slow reader loses pictures rather than delaying the others
			}
		}
		r.mu.Unlock()
	}
}

func (r *videoReceiver) counts() (uint64, time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.pictures, r.last
}

func (r *videoReceiver) subscribe() chan []byte {
	c := make(chan []byte, 64)
	r.mu.Lock()
	r.clients[c] = struct{}{}
	r.mu.Unlock()
	return c
}

func (r *videoReceiver) unsubscribe(c chan []byte) {
	r.mu.Lock()
	delete(r.clients, c)
	r.mu.Unlock()
}

// serveReceived streams the received video: GET /video/received[?seconds=N] (1 to 300; default until the client goes)
// answers video/h264 from the next IDR picture on. The stream ends after N seconds, when the client goes, or when no
// picture comes for ?idle_s (default 10) seconds after the first.
func (v *videoFeed) serveReceived(w http.ResponseWriter, r *http.Request) {
	if v == nil || v.received == nil {
		writeFeedReply(w, http.StatusNotFound, map[string]any{"ok": false, "code": codeNotEnabled,
			"error": "this agent does not receive video (video.receive)"})
		return
	}
	seconds, idle := queryInt(r, "seconds", 0, 300), queryInt(r, "idle_s", 10, 300)
	var deadline <-chan time.Time
	if seconds > 0 {
		timer := time.NewTimer(time.Duration(seconds) * time.Second)
		defer timer.Stop()
		deadline = timer.C
	}
	pictures := v.received.subscribe()
	defer v.received.unsubscribe(pictures)
	w.Header().Set("Content-Type", "video/h264")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	started := false
	quiet := time.NewTimer(time.Duration(max(idle, 1)) * time.Second)
	defer quiet.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-deadline:
			return
		case <-quiet.C:
			if started {
				return // the transmission ended
			}
			quiet.Reset(time.Duration(max(idle, 1)) * time.Second)
		case picture := <-pictures:
			if !started && picture[0] != 1 {
				continue // decoding starts at an IDR picture
			}
			started = true
			quiet.Reset(time.Duration(max(idle, 1)) * time.Second)
			if _, err := w.Write(picture[5:]); err != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
	}
}

// queryInt is a query parameter as an integer within [0, limit], def when it is missing or not a number.
func queryInt(r *http.Request, name string, def, limit int) int {
	n, err := strconv.Atoi(r.URL.Query().Get(name))
	if err != nil || n < 0 {
		return def
	}
	return min(n, limit)
}
