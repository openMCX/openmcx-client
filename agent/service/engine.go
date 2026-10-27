//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
)

// engine runs mcx-client and reaches it through its control FIFO and status file; each
// JSON event it prints goes to onEvent. Adapted from the dispatcher's engine, without
// live audio.
type engine struct {
	path, config string
	args         []string
	fifo, status string
	userID       string // mcdata.user_id: the agent's MCData ID
	onEvent      func(fields map[string]any)
	onExit       func()

	mu   sync.Mutex
	cmd  *exec.Cmd
	done chan struct{}
	tail []string
}

func newEngine(path, config string, args []string) (*engine, error) {
	cfg, err := readJSON(config)
	if err != nil {
		return nil, err
	}
	e := &engine{path: path, config: config, args: args}
	e.fifo, _ = cfg["control_fifo"].(string)
	e.status, _ = cfg["status_file"].(string)
	mcdata, _ := cfg["mcdata"].(map[string]any)
	e.userID, _ = mcdata["user_id"].(string)
	if e.fifo == "" || e.status == "" || e.userID == "" {
		return nil, errors.New("the engine configuration needs control_fifo, status_file and mcdata.user_id")
	}
	return e, nil
}

func readJSON(path string) (map[string]any, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var v map[string]any
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return v, nil
}

// writeAtomic writes a file only its owner may read, replacing it atomically.
func writeAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// start runs the engine; its old status is removed first, so that what the daemon reads is
// this run's.
func (e *engine) start() error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cmd != nil {
		return errors.New("the engine is already running")
	}
	_ = os.Remove(e.status)
	cmd := exec.Command(e.path, append([]string{"--config", e.config}, e.args...)...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	e.cmd, e.done, e.tail = cmd, make(chan struct{}), nil
	var readers sync.WaitGroup
	for _, r := range []io.Reader{stdout, stderr} {
		readers.Add(1)
		go func(r io.Reader) {
			defer readers.Done()
			scanner := bufio.NewScanner(r)
			scanner.Buffer(make([]byte, 64*1024), 1024*1024)
			for scanner.Scan() {
				line := scanner.Text()
				log.Printf("engine: %s", line)
				e.mu.Lock()
				if e.tail = append(e.tail, line); len(e.tail) > 50 {
					e.tail = e.tail[1:]
				}
				e.mu.Unlock()
				var fields map[string]any
				if json.Unmarshal([]byte(line), &fields) == nil && fields["event"] != nil && e.onEvent != nil {
					e.onEvent(fields)
				}
			}
		}(r)
	}
	done := e.done
	go func() {
		readers.Wait()
		err := cmd.Wait()
		log.Printf("engine ended: %v", err)
		e.mu.Lock()
		e.cmd = nil
		e.mu.Unlock()
		close(done)
		if e.onExit != nil {
			e.onExit()
		}
	}()
	return nil
}

// stop ends the engine: SIGTERM, then SIGKILL after five seconds.
func (e *engine) stop() {
	e.mu.Lock()
	cmd, done := e.cmd, e.done
	e.mu.Unlock()
	if cmd == nil {
		return
	}
	_ = cmd.Process.Signal(syscall.SIGTERM)
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		_ = cmd.Process.Kill()
		<-done
	}
}

// running reports whether the engine runs, and the channel closed when it ends.
func (e *engine) running() (bool, chan struct{}) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.cmd != nil, e.done
}

// lastExit is the engine's last "mcx-client: " line, its exit message.
func (e *engine) lastExit() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	for i := len(e.tail) - 1; i >= 0; i-- {
		if msg, ok := strings.CutPrefix(e.tail[i], "mcx-client: "); ok {
			return msg
		}
	}
	return ""
}

// command writes one line to the engine's control FIFO. A line shorter than PIPE_BUF
// (4096 bytes) is written in one piece; the daemon keeps every line well below it.
func (e *engine) command(line string) error {
	if strings.ContainsAny(line, "\n\r") {
		return errors.New("a command is one line")
	}
	if len(line) >= 4000 {
		return errors.New("the command line is too long")
	}
	f, err := os.OpenFile(e.fifo, os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return fmt.Errorf("the engine is not ready: %w", err)
	}
	defer f.Close()
	_, err = f.WriteString(line + "\n")
	return err
}

// state is the engine's status document, as it writes it.
func (e *engine) state() (map[string]any, error) {
	return readJSON(e.status)
}
