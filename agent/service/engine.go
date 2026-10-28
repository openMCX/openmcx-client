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
	"path/filepath"
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
	userID       string // mcdata.user_id: the agent's MCData ID; empty without MCData
	mcpttID      string // mcptt.user_id
	mcvideoID    string // mcvideo.user_id; empty without MCVideo
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
	e.userID = userID(cfg, "mcdata")
	e.mcpttID = userID(cfg, "mcptt")
	e.mcvideoID = userID(cfg, "mcvideo")
	if e.fifo == "" || e.status == "" {
		return nil, errors.New("the engine configuration needs control_fifo and status_file")
	}
	return e, nil
}

// userID is the user_id of a service's part of the engine configuration, empty without it.
func userID(cfg map[string]any, service string) string {
	part, _ := cfg[service].(map[string]any)
	id, _ := part["user_id"].(string)
	return id
}

// runtimeConfig is the engine configuration the daemon runs: the configured one, and for voice or video a copy in
// data_dir with the agent's media settings: received talk bursts as WAV files in speechDir (media.speech_dir), no
// call recording, incoming calls answered at once; pictures from videoFIFO (mcvideo.live), received video not taken.
func runtimeConfig(c *config, speechDir, videoFIFO string) (string, error) {
	if !c.Voice.Enabled && !c.Video.Enabled {
		return c.Engine.Config, nil
	}
	cfg, err := readJSON(c.Engine.Config)
	if err != nil {
		return "", err
	}
	part := func(name string) map[string]any {
		m, _ := cfg[name].(map[string]any)
		if m == nil {
			m = map[string]any{}
			cfg[name] = m
		}
		return m
	}
	if c.Voice.Enabled {
		media := part("media")
		media["speech_dir"] = speechDir
		media["keep_received_audio"] = false
		if _, set := part("mcptt")["answer_mode"]; !set {
			part("mcptt")["answer_mode"] = "auto"
		}
	}
	if c.Video.Enabled {
		if cfg["mcvideo"] == nil {
			return "", errors.New("video needs MCVideo in the engine configuration (mcvideo)")
		}
		mcvideo := part("mcvideo")
		mcvideo["live"] = map[string]any{"fifo": videoFIFO, "width": c.Video.Width, "height": c.Video.Height,
			"frame_rate": c.Video.FrameRate, "bitrate": c.Video.Bitrate}
		// An agent that shows its video does not watch others'; one with video.receive does (mcvideo.live_receive).
		mcvideo["auto_receive"] = c.Video.Receive
		if c.Video.Receive {
			mcvideo["live_receive"] = map[string]any{"send_host": "127.0.0.1", "send_port": c.Video.ReceivePort}
		}
		if _, set := mcvideo["answer_mode"]; !set {
			mcvideo["answer_mode"] = "auto"
		}
	}
	raw, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return "", err
	}
	file := filepath.Join(c.DataDir, "engine.json")
	return file, writeAtomic(file, raw)
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
