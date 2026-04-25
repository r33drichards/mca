package main

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/r33drichards/mca/clients/go/btone"
)

func main() {
	c, err := btone.New()
	if err != nil { fmt.Println("ERR:", err); os.Exit(1) }
	raw, err := c.RpcDiscover()
	if err != nil { fmt.Println("ERR:", err); os.Exit(1) }
	var spec struct{ Methods []any `json:"methods"` }
	json.Unmarshal(raw, &spec)
	fmt.Println("go: rpc.discover ok, methods=", len(spec.Methods))
	raw, err = c.PlayerState()
	if err != nil { fmt.Println("ERR:", err); os.Exit(1) }
	var ps struct{ InWorld bool `json:"inWorld"` }
	json.Unmarshal(raw, &ps)
	fmt.Println("go: player.state inWorld=", ps.InWorld)
}
