package main

import (
	"log"
	"net"
	"sync"
)

// audioBridge carries PCM (16 kHz, 16-bit little-endian, mono) between browsers and the
// engine: frames from a browser go to the engine's media.live port; frames the engine
// receives come here and go to every connected browser.
type audioBridge struct {
	conn    *net.UDPConn
	engine  *net.UDPAddr
	mu      sync.Mutex
	clients map[chan []byte]struct{}
}

func newAudioBridge(engineIn, listen string) (*audioBridge, error) {
	engine, err := net.ResolveUDPAddr("udp", engineIn)
	if err != nil {
		return nil, err
	}
	local, err := net.ResolveUDPAddr("udp", listen)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp", local)
	if err != nil {
		return nil, err
	}
	b := &audioBridge{conn: conn, engine: engine, clients: map[chan []byte]struct{}{}}
	go b.receive()
	return b, nil
}

func (b *audioBridge) receive() {
	buf := make([]byte, 4096)
	for {
		n, _, err := b.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("audio: %v", err)
			return
		}
		frame := append([]byte(nil), buf[:n]...)
		b.mu.Lock()
		for c := range b.clients {
			select {
			case c <- frame:
			default: // a slow browser loses frames rather than delaying the others
			}
		}
		b.mu.Unlock()
	}
}

// send forwards microphone PCM to the engine.
func (b *audioBridge) send(pcm []byte) {
	_, _ = b.conn.WriteToUDP(pcm, b.engine)
}

func (b *audioBridge) subscribe() chan []byte {
	c := make(chan []byte, 64)
	b.mu.Lock()
	b.clients[c] = struct{}{}
	b.mu.Unlock()
	return c
}

func (b *audioBridge) unsubscribe(c chan []byte) {
	b.mu.Lock()
	delete(b.clients, c)
	b.mu.Unlock()
}
