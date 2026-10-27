//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"errors"
	"sync"
	"time"
)

// notifier wakes everyone waiting for a change: each change closes the channel waiters hold
// and makes a new one. A waiter takes the channel before it looks at the state, so that no
// change between the look and the wait is lost.
type notifier struct {
	mu sync.Mutex
	ch chan struct{}
}

func newNotifier() *notifier { return &notifier{ch: make(chan struct{})} }

func (s *notifier) wait() <-chan struct{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ch
}

func (s *notifier) fire() {
	s.mu.Lock()
	defer s.mu.Unlock()
	close(s.ch)
	s.ch = make(chan struct{})
}

var errTimeout = errors.New("timed out")

// waitUntil checks done at once and after each change until it reports true, an error, or
// the timeout passes (errTimeout).
func (s *notifier) waitUntil(timeout time.Duration, done func() (bool, error)) error {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for {
		changed := s.wait()
		ok, err := done()
		if err != nil || ok {
			return err
		}
		select {
		case <-changed:
		case <-deadline.C:
			return errTimeout
		}
	}
}

// engineEvent is one JSON event of the engine, numbered in the order the daemon read it.
type engineEvent struct {
	Seq    uint64
	Kind   string
	Fields map[string]any
}

// journal keeps the engine's last events, so that a command can find the events that
// followed it.
type journal struct {
	mu      sync.Mutex
	events  []engineEvent
	next    uint64
	changed *notifier
}

const journalSize = 1000

func newJournal(changed *notifier) *journal { return &journal{next: 1, changed: changed} }

func (j *journal) append(fields map[string]any) {
	kind, _ := fields["event"].(string)
	j.mu.Lock()
	j.events = append(j.events, engineEvent{Seq: j.next, Kind: kind, Fields: fields})
	j.next++
	if len(j.events) > journalSize {
		j.events = j.events[len(j.events)-journalSize:]
	}
	j.mu.Unlock()
	j.changed.fire()
}

// last is the number of the newest event, 0 before the first.
func (j *journal) last() uint64 {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.next - 1
}

// since is the events numbered above seq, oldest first.
func (j *journal) since(seq uint64) []engineEvent {
	j.mu.Lock()
	defer j.mu.Unlock()
	var out []engineEvent
	for _, ev := range j.events {
		if ev.Seq > seq {
			out = append(out, ev)
		}
	}
	return out
}
