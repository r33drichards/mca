// Example: drive btone-mod-c with the generated Go client.
//
// Run with:
//   cd clients/go && nix develop ../.. --command go run ../../examples/go-example.go
package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"github.com/r33drichards/mca/clients/go/btone"
)

func main() {
	c, err := btone.New()
	must(err)
	fmt.Println("=== go example ===")

	// 1. Self-introspect
	raw, err := c.RpcDiscover()
	must(err)
	var spec struct{ Methods []json.RawMessage `json:"methods"` }
	must(json.Unmarshal(raw, &spec))
	fmt.Printf("discovered %d methods\n", len(spec.Methods))

	// 2. Read player state
	raw, err = c.PlayerState()
	must(err)
	var state struct {
		InWorld  bool   `json:"inWorld"`
		Name     string `json:"name"`
		Health   float64 `json:"health"`
		Food     int    `json:"food"`
		BlockPos struct{ X, Y, Z int } `json:"blockPos"`
	}
	must(json.Unmarshal(raw, &state))
	fmt.Printf("state: inWorld=%v name=%s hp=%.1f food=%d pos=%v\n",
		state.InWorld, state.Name, state.Health, state.Food, state.BlockPos)

	// 3. Inventory snippet
	raw, err = c.PlayerInventory()
	must(err)
	var inv struct {
		Main []struct {
			Slot  int    `json:"slot"`
			Id    string `json:"id"`
			Count int    `json:"count"`
		} `json:"main"`
	}
	must(json.Unmarshal(raw, &inv))
	for _, s := range inv.Main[:min(3, len(inv.Main))] {
		fmt.Printf("  slot=%d %s x%d\n", s.Slot, s.Id, s.Count)
	}

	// 4. Screenshot
	raw, err = c.WorldScreenshot(map[string]any{"width": 480, "yaw": 180, "pitch": -5})
	must(err)
	var shot struct{ Image string `json:"image"` }
	must(json.Unmarshal(raw, &shot))
	img, err := base64.StdEncoding.DecodeString(shot.Image)
	must(err)
	out := filepath.Join(os.TempDir(), "btone-go-shot.png")
	must(os.WriteFile(out, img, 0644))
	fmt.Printf("wrote %d bytes to %s\n", len(img), out)

	// 5. Low-level Call() with custom decode
	raw, err = c.Call("rpc.discover", nil)
	must(err)
	var fullSpec struct {
		Methods []struct{ Name string `json:"name"` } `json:"methods"`
	}
	must(json.Unmarshal(raw, &fullSpec))
	count := 0
	for _, m := range fullSpec.Methods {
		if len(m.Name) >= 9 && m.Name[:9] == "baritone." {
			count++
		}
	}
	fmt.Printf("baritone.* methods: %d\n", count)

	fmt.Println("OK")
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
