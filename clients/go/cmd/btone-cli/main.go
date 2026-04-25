// btone-cli — JSON-RPC CLI for btone-mod-c.
//
// Usage:
//   btone-cli <method> [--params '<json>']      # POST { method, params } and print result
//   btone-cli list                              # list all methods (read from rpc.discover)
//   btone-cli describe <method>                 # show params/result schema for a method
//   btone-cli rpc.discover                      # full OpenRPC schema
//
// Examples:
//   btone-cli player.state
//   btone-cli baritone.goto --params '{"x":1004,"y":69,"z":822}'
//   btone-cli world.place_block --params '{"x":1004,"y":67,"z":820,"side":"up"}'
//
// Reads ~/btone-mc-work/config/btone-bridge.json by default.
// Pipe-friendly: result JSON to stdout, errors to stderr, non-zero exit on failure.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"

	"github.com/r33drichards/mca/clients/go/btone"
)

const usage = `btone-cli — JSON-RPC CLI for btone-mod-c

USAGE:
  btone-cli <method> [--params '<json>']
  btone-cli list                              List all methods
  btone-cli describe <method>                 Show schema for a method
  btone-cli help

FLAGS:
  --params <json>     Inline JSON params object
  --params-file <p>   Read params from a file (- for stdin)
  --config <p>        Path to btone-bridge.json (default: ~/btone-mc-work/config/btone-bridge.json)
  --pretty            Pretty-print JSON output
  --raw               Print just .result (default), or pass --raw=false for full envelope

EXAMPLES:
  btone-cli player.state
  btone-cli baritone.goto --params '{"x":1004,"y":69,"z":822}'
  btone-cli world.screenshot --params '{"width":640,"yaw":180}' | jq -r '.image' | base64 -d > view.png
  btone-cli describe player.teleport
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}

	cmd := os.Args[1]
	args := os.Args[2:]

	switch cmd {
	case "help", "-h", "--help":
		fmt.Print(usage)
		return
	case "list":
		runList(args)
		return
	case "describe":
		runDescribe(args)
		return
	}

	// Otherwise treat cmd as a method name.
	runRpc(cmd, args)
}

// ----- shared flag parsing -----

type rpcFlags struct {
	params     string
	paramsFile string
	configPath string
	pretty     bool
	raw        bool
}

func parseRpcFlags(args []string) (*rpcFlags, []string) {
	fs := flag.NewFlagSet("btone-cli", flag.ExitOnError)
	f := &rpcFlags{}
	fs.StringVar(&f.params, "params", "", "inline JSON params")
	fs.StringVar(&f.paramsFile, "params-file", "", "path to params JSON (- for stdin)")
	fs.StringVar(&f.configPath, "config", "", "btone-bridge.json path")
	fs.BoolVar(&f.pretty, "pretty", false, "pretty-print JSON output")
	fs.BoolVar(&f.raw, "raw", true, "print just .result (default true)")
	_ = fs.Parse(args)
	return f, fs.Args()
}

// ----- list -----

func runList(args []string) {
	c, err := btone.NewWithConfig(""); fatal(err)
	raw, err := c.Call("rpc.discover", nil); fatal(err)
	var spec struct {
		Methods []struct {
			Name    string `json:"name"`
			Summary string `json:"summary"`
		} `json:"methods"`
	}
	fatal(json.Unmarshal(raw, &spec))
	for _, m := range spec.Methods {
		fmt.Printf("%-32s  %s\n", m.Name, m.Summary)
	}
}

// ----- describe -----

func runDescribe(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "describe: method name required")
		os.Exit(2)
	}
	want := args[0]
	c, err := btone.NewWithConfig(""); fatal(err)
	raw, err := c.Call("rpc.discover", nil); fatal(err)
	var spec struct {
		Methods []json.RawMessage `json:"methods"`
	}
	fatal(json.Unmarshal(raw, &spec))
	for _, m := range spec.Methods {
		var head struct{ Name string `json:"name"` }
		json.Unmarshal(m, &head)
		if head.Name == want {
			var pretty json.RawMessage
			pretty = m
			out, _ := json.MarshalIndent(pretty, "", "  ")
			fmt.Println(string(out))
			return
		}
	}
	fmt.Fprintf(os.Stderr, "describe: method %q not found\n", want)
	os.Exit(1)
}

// ----- rpc -----

func runRpc(method string, args []string) {
	f, _ := parseRpcFlags(args)

	var params any
	if f.params != "" {
		if err := json.Unmarshal([]byte(f.params), &params); err != nil {
			fmt.Fprintf(os.Stderr, "bad --params JSON: %v\n", err)
			os.Exit(2)
		}
	} else if f.paramsFile != "" {
		var data []byte
		var err error
		if f.paramsFile == "-" {
			data, err = io.ReadAll(os.Stdin)
		} else {
			data, err = os.ReadFile(f.paramsFile)
		}
		fatal(err)
		fatal(json.Unmarshal(data, &params))
	}

	c, err := btone.NewWithConfig(f.configPath); fatal(err)
	raw, rpcErr := c.Call(method, params)
	if rpcErr != nil {
		fmt.Fprintf(os.Stderr, "%s: %v\n", method, rpcErr)
		os.Exit(1)
	}

	var pretty []byte
	if f.pretty {
		var v any
		json.Unmarshal(raw, &v)
		pretty, _ = json.MarshalIndent(v, "", "  ")
	} else {
		pretty = raw
	}
	os.Stdout.Write(pretty)
	if len(pretty) > 0 && pretty[len(pretty)-1] != '\n' {
		os.Stdout.Write([]byte{'\n'})
	}
}

func fatal(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}
