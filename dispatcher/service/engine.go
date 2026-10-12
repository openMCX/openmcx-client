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

// engine runs mcx-client and reaches it through its control FIFO and status file. The
// FIFO, status file and live audio ports come from the base configuration: the full
// configuration in lab mode, the position's device settings in position mode.
type engine struct {
	path         string
	args         []string
	fifo, status string
	liveIn       string // the engine's media.live port: microphone PCM to it
	liveOut      string // where the engine sends received PCM: this service
	onLine       func(line string)

	mu     sync.Mutex
	config string // the configuration the engine runs with
	cmd    *exec.Cmd
	done   chan struct{}
	tail   []string
}

func newEngine(path, base string, args []string) (*engine, error) {
	cfg, err := readJSON(base)
	if err != nil {
		return nil, err
	}
	e := &engine{path: path, config: base, args: args}
	e.fifo, _ = cfg["control_fifo"].(string)
	e.status, _ = cfg["status_file"].(string)
	media, _ := cfg["media"].(map[string]any)
	live, _ := media["live"].(map[string]any)
	if e.fifo == "" || e.status == "" || live == nil {
		return nil, errors.New("the engine configuration needs control_fifo, status_file and media.live")
	}
	host := func(key, fallback string) string {
		if v, ok := live[key].(string); ok && v != "" {
			return v
		}
		return fallback
	}
	e.liveIn = fmt.Sprintf("%s:%v", host("listen", "127.0.0.1"), live["port"])
	e.liveOut = fmt.Sprintf("%s:%v", host("send_host", "127.0.0.1"), live["send_port"])
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

// writeSecret writes a file only its owner may read, replacing it atomically.
func writeSecret(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// start runs the engine with config and extra arguments; its old status is removed first,
// so that what the service reads is this run's.
func (e *engine) start(config string, extra ...string) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cmd != nil {
		return errors.New("the engine is already running")
	}
	_ = os.Remove(e.status)
	cmd := exec.Command(e.path, append(append([]string{"--config", config}, extra...), e.args...)...)
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
	e.config, e.cmd, e.done, e.tail = config, cmd, make(chan struct{}), nil
	var readers sync.WaitGroup
	for _, r := range []io.Reader{stdout, stderr} {
		readers.Add(1)
		go func(r io.Reader) {
			defer readers.Done()
			scanner := bufio.NewScanner(r)
			scanner.Buffer(make([]byte, 64*1024), 1024*1024)
			for scanner.Scan() {
				line := scanner.Text()
				fmt.Println(line)
				e.mu.Lock()
				if e.tail = append(e.tail, line); len(e.tail) > 50 {
					e.tail = e.tail[1:]
				}
				e.mu.Unlock()
				if e.onLine != nil {
					e.onLine(line)
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
	}()
	return nil
}

// stop ends the engine: SIGTERM (it releases its call), then SIGKILL after five seconds.
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

// lastError is the engine's last error line (its exit message or a client_error reason).
func (e *engine) lastError() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	for i := len(e.tail) - 1; i >= 0; i-- {
		line := e.tail[i]
		if msg, ok := strings.CutPrefix(line, "mcx-client: "); ok {
			return msg
		}
		var ev struct{ Event, Reason string }
		if json.Unmarshal([]byte(line), &ev) == nil && ev.Event == "client_error" {
			return ev.Reason
		}
	}
	return ""
}

// supervise runs the engine for good (lab mode) and starts it again when it ends.
func (e *engine) supervise() {
	for {
		if err := e.start(e.configPath()); err != nil {
			log.Printf("engine: %v", err)
		} else {
			_, done := e.running()
			<-done
		}
		time.Sleep(3 * time.Second)
	}
}

// command writes one line to the engine's control FIFO.
func (e *engine) command(line string) error {
	if strings.ContainsAny(line, "\n\r") {
		return errors.New("a command is one line")
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

func (e *engine) configPath() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.config
}
