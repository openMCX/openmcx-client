//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"encoding/json"
	"os"
	"testing"
)

// The tools of this version of the API contract, as MCP lists them, against the committed snapshot: a change to a
// tool, an argument or a description is deliberate. After one, raise version (main.go) and write the snapshot again:
//
//	UPDATE_SNAPSHOT=1 go test -run TestToolsSnapshot ./...
func TestToolsSnapshot(t *testing.T) {
	const file = "testdata/tools_snapshot.json"
	now, _ := json.MarshalIndent(map[string]any{"version": version, "tools": toolList()}, "", "  ")
	now = append(now, '\n')
	if os.Getenv("UPDATE_SNAPSHOT") == "1" {
		if err := os.WriteFile(file, now, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	saved, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if string(saved) != string(now) {
		t.Fatalf("the tools differ from %s: raise version in main.go if the contract changed, then run "+
			"UPDATE_SNAPSHOT=1 go test -run TestToolsSnapshot ./...", file)
	}
}
