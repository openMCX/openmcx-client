//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"log"
	"net"
	"sync"
	"time"

	"golang.org/x/net/websocket"
)

// videoBridge carries the MCVideo pictures the engine receives (mcvideo.live_receive: one H.264 picture per
// datagram) to every connected browser, which decodes them. Video goes one way: the client page transmits none.
type videoBridge struct {
	conn    *net.UDPConn
	mu      sync.Mutex
	clients map[chan []byte]struct{}
}

func newVideoBridge(listen string) (*videoBridge, error) {
	local, err := net.ResolveUDPAddr("udp", listen)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp", local)
	if err != nil {
		return nil, err
	}
	_ = conn.SetReadBuffer(4 << 20)
	b := &videoBridge{conn: conn, clients: map[chan []byte]struct{}{}}
	go b.receive()
	return b, nil
}

func (b *videoBridge) receive() {
	buf := make([]byte, 65536)
	for {
		n, _, err := b.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("video: %v", err)
			return
		}
		picture := append([]byte(nil), buf[:n]...)
		b.mu.Lock()
		for c := range b.clients {
			select {
			case c <- picture:
			default: // a slow browser loses pictures (and waits for the next IDR picture) rather than lag
			}
		}
		b.mu.Unlock()
	}
}

func (b *videoBridge) subscribe() chan []byte {
	c := make(chan []byte, 32)
	b.mu.Lock()
	b.clients[c] = struct{}{}
	b.mu.Unlock()
	return c
}

func (b *videoBridge) unsubscribe(c chan []byte) {
	b.mu.Lock()
	delete(b.clients, c)
	b.mu.Unlock()
}

// videoSocket sends the received pictures to one browser; without mcvideo.live_receive it closes at once.
func (a *api) videoSocket(ws *websocket.Conn) {
	defer ws.Close()
	if a.video == nil {
		return
	}
	ws.PayloadType = websocket.BinaryFrame
	out := a.video.subscribe()
	defer a.video.unsubscribe(out)
	done := make(chan struct{})
	go func() { // the browser sends nothing; a read ends when it goes
		defer close(done)
		var ignored []byte
		for websocket.Message.Receive(ws, &ignored) == nil {
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
				return
			}
		case picture := <-out:
			if err := websocket.Message.Send(ws, picture); err != nil {
				return
			}
		}
	}
}
