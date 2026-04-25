#!/usr/bin/env python3
"""
Read proto/btone-openrpc.json and emit:
  - clients/go/btone/btone.go         (typed Go client + one method per RPC)
  - clients/python/btone_client.py    (typed Python client + methods)
  - clients/typescript/src/btone_client.ts  (TS client + methods)

Each generated client:
  - Reads ~/btone-mc-work/config/btone-bridge.json by default for port + token.
  - Exposes a low-level `call(method, params)` for ad-hoc methods.
  - Has one typed method per RPC, named per-language convention
    (player.state -> Go PlayerState / Python player_state / TS playerState).

Re-run after mod schema changes:
    python3 bin/generate-clients.py
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC_PATH = ROOT / "proto" / "btone-openrpc.json"

GO_DIR = ROOT / "clients" / "go" / "btone"
PY_DIR = ROOT / "clients" / "python"
TS_DIR = ROOT / "clients" / "typescript" / "src"


# ----- name helpers -----

def to_pascal(name: str) -> str:
    return "".join(part.capitalize() for part in re.split(r"[._-]", name) if part)


def to_snake(name: str) -> str:
    return re.sub(r"[.\-]", "_", name)


def to_camel(name: str) -> str:
    parts = [p for p in re.split(r"[._-]", name) if p]
    return parts[0].lower() + "".join(p.capitalize() for p in parts[1:])


def go_keyword_safe(s: str) -> str:
    return s + "_" if s in {"type", "default", "func", "range", "select", "package", "import"} else s


# ----- Python -----

PY_HEADER = '''\
"""
btone-client — JSON-RPC client for btone-mod-c.

GENERATED FROM proto/btone-openrpc.json — DO NOT EDIT BY HAND.
Run python3 bin/generate-clients.py to regenerate after mod schema changes.

Usage:
    from btone_client import BtoneClient
    bot = BtoneClient()                      # reads ~/btone-mc-work/config/btone-bridge.json
    state = bot.player_state()
    bot.baritone_goto({"x": 1004, "y": 69, "z": 822})
    # or low-level:
    bot.call("rpc.discover")
"""
from __future__ import annotations

import json
import os
import urllib.request
from pathlib import Path
from typing import Any, Mapping, Optional


DEFAULT_CONFIG = Path.home() / "btone-mc-work" / "config" / "btone-bridge.json"


class BtoneError(RuntimeError):
    """RPC returned ok=false."""

    def __init__(self, code: str, message: str, raw: Mapping[str, Any]):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.raw = raw


class BtoneClient:
    def __init__(
        self,
        host: str = "127.0.0.1",
        port: Optional[int] = None,
        token: Optional[str] = None,
        config_path: Path = DEFAULT_CONFIG,
        timeout: float = 30.0,
    ):
        if port is None or token is None:
            cfg = json.loads(config_path.read_text())
            port = port if port is not None else cfg["port"]
            token = token if token is not None else cfg["token"]
        self._url = f"http://{host}:{port}/rpc"
        self._token = token
        self._timeout = timeout

    def call(self, method: str, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Low-level: POST {method, params} and return .result, or raise BtoneError."""
        body = {"method": method}
        if params is not None:
            body["params"] = params
        req = urllib.request.Request(
            self._url,
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self._token}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=self._timeout) as resp:
            payload = json.loads(resp.read())
        if not payload.get("ok"):
            err = payload.get("error", {})
            raise BtoneError(err.get("code", "unknown"), err.get("message", ""), payload)
        return payload.get("result")
'''


def gen_python(spec: dict) -> str:
    out = [PY_HEADER]
    out.append("\n    # ---------- typed methods (auto-generated) ----------\n")
    for m in spec["methods"]:
        name = m["name"]
        py_name = to_snake(name)
        params = m.get("params", [])
        summary = m.get("summary", "").replace('"', '\\"')

        if not params:
            sig = f"    def {py_name}(self) -> Any:\n"
            body = f"        return self.call({name!r})\n"
        else:
            # Take params dict; let user pass any subset.
            param_names = ", ".join(p["name"] for p in params)
            sig = f"    def {py_name}(self, params: Optional[Mapping[str, Any]] = None) -> Any:\n"
            body = (
                f"        return self.call({name!r}, params)\n"
            )
        doc = f'        """{summary}"""\n' if summary else ""
        # Param doc lines
        if params:
            doc += '        # Params: ' + ", ".join(
                f"{p['name']}{'' if p.get('required') else '?'}" for p in params
            ) + "\n"
        out.append(sig)
        out.append(doc)
        out.append(body)
        out.append("\n")
    return "".join(out)


# ----- Go -----

GO_HEADER = '''\
// btone — JSON-RPC client for btone-mod-c.
//
// GENERATED FROM proto/btone-openrpc.json — DO NOT EDIT BY HAND.
// Run python3 bin/generate-clients.py to regenerate after mod schema changes.
package btone

import (
\t"bytes"
\t"encoding/json"
\t"fmt"
\t"net/http"
\t"os"
\t"path/filepath"
\t"time"
)

type Config struct {
\tPort  int    `json:"port"`
\tToken string `json:"token"`
}

func DefaultConfigPath() string {
\thome, _ := os.UserHomeDir()
\treturn filepath.Join(home, "btone-mc-work", "config", "btone-bridge.json")
}

func LoadConfig(path string) (Config, error) {
\tvar c Config
\tif path == "" {
\t\tpath = DefaultConfigPath()
\t}
\tb, err := os.ReadFile(path)
\tif err != nil {
\t\treturn c, fmt.Errorf("read btone config %s: %w", path, err)
\t}
\tif err := json.Unmarshal(b, &c); err != nil {
\t\treturn c, fmt.Errorf("parse btone config: %w", err)
\t}
\treturn c, nil
}

type Client struct {
\turl     string
\ttoken   string
\thttp    *http.Client
}

func New() (*Client, error) {
\treturn NewWithConfig("")
}

func NewWithConfig(configPath string) (*Client, error) {
\tcfg, err := LoadConfig(configPath)
\tif err != nil {
\t\treturn nil, err
\t}
\treturn NewClient("127.0.0.1", cfg.Port, cfg.Token), nil
}

func NewClient(host string, port int, token string) *Client {
\treturn &Client{
\t\turl:   fmt.Sprintf("http://%s:%d/rpc", host, port),
\t\ttoken: token,
\t\thttp:  &http.Client{Timeout: 30 * time.Second},
\t}
}

type rpcEnvelope struct {
\tOk     bool             `json:"ok"`
\tResult json.RawMessage  `json:"result,omitempty"`
\tError  *RpcError        `json:"error,omitempty"`
}

type RpcError struct {
\tCode    string `json:"code"`
\tMessage string `json:"message"`
}

func (e *RpcError) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

// Call is the low-level entry point. Marshal params, POST, return raw result bytes.
// Use the typed methods below for everyday work.
func (c *Client) Call(method string, params any) (json.RawMessage, error) {
\treq := map[string]any{"method": method}
\tif params != nil {
\t\treq["params"] = params
\t}
\tbody, err := json.Marshal(req)
\tif err != nil {
\t\treturn nil, err
\t}
\thttpReq, err := http.NewRequest("POST", c.url, bytes.NewReader(body))
\tif err != nil {
\t\treturn nil, err
\t}
\thttpReq.Header.Set("Authorization", "Bearer "+c.token)
\thttpReq.Header.Set("Content-Type", "application/json")
\tresp, err := c.http.Do(httpReq)
\tif err != nil {
\t\treturn nil, err
\t}
\tdefer resp.Body.Close()
\tvar env rpcEnvelope
\tif err := json.NewDecoder(resp.Body).Decode(&env); err != nil {
\t\treturn nil, fmt.Errorf("decode response: %w", err)
\t}
\tif !env.Ok {
\t\tif env.Error == nil {
\t\t\treturn nil, fmt.Errorf("rpc failed without error body")
\t\t}
\t\treturn nil, env.Error
\t}
\treturn env.Result, nil
}

// CallTyped is a convenience wrapper that decodes result into target.
func (c *Client) CallTyped(method string, params any, target any) error {
\traw, err := c.Call(method, params)
\tif err != nil {
\t\treturn err
\t}
\tif target == nil {
\t\treturn nil
\t}
\treturn json.Unmarshal(raw, target)
}
'''


def gen_go(spec: dict) -> str:
    out = [GO_HEADER]
    out.append("\n// ---------- typed methods (auto-generated) ----------\n")
    for m in spec["methods"]:
        name = m["name"]
        go_name = to_pascal(name)
        params = m.get("params", [])
        summary = m.get("summary", "")
        if summary:
            for line in summary.split("\n"):
                out.append(f"// {line}\n")
        if not params:
            out.append(
                f"func (c *Client) {go_name}() (json.RawMessage, error) {{\n"
                f"\treturn c.Call({json.dumps(name)}, nil)\n"
                f"}}\n\n"
            )
        else:
            out.append(
                f"func (c *Client) {go_name}(params map[string]any) (json.RawMessage, error) {{\n"
                f"\treturn c.Call({json.dumps(name)}, params)\n"
                f"}}\n\n"
            )
    return "".join(out)


# ----- TypeScript -----

TS_HEADER = '''\
// btone — JSON-RPC client for btone-mod-c.
//
// GENERATED FROM proto/btone-openrpc.json — DO NOT EDIT BY HAND.
// Run python3 bin/generate-clients.py to regenerate after mod schema changes.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export interface BtoneConfig {
  port: number;
  token: string;
}

export class BtoneError extends Error {
  constructor(public code: string, public message: string, public raw: any) {
    super(`${code}: ${message}`);
  }
}

export class BtoneClient {
  private url: string;
  private token: string;
  public timeoutMs: number;

  constructor(opts: {
    host?: string;
    port?: number;
    token?: string;
    configPath?: string;
    timeoutMs?: number;
  } = {}) {
    let port = opts.port;
    let token = opts.token;
    if (port === undefined || token === undefined) {
      const path = opts.configPath ?? join(homedir(), "btone-mc-work", "config", "btone-bridge.json");
      const cfg: BtoneConfig = JSON.parse(readFileSync(path, "utf8"));
      port = port ?? cfg.port;
      token = token ?? cfg.token;
    }
    this.url = `http://${opts.host ?? "127.0.0.1"}:${port}/rpc`;
    this.token = token;
    this.timeoutMs = opts.timeoutMs ?? 30_000;
  }

  /** Low-level call. Use typed methods below for everyday work. */
  async call<T = any>(method: string, params?: object): Promise<T> {
    const body: any = { method };
    if (params !== undefined) body.params = params;
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), this.timeoutMs);
    try {
      const resp = await fetch(this.url, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${this.token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
      const env = await resp.json();
      if (!env.ok) {
        const err = env.error ?? {};
        throw new BtoneError(err.code ?? "unknown", err.message ?? "", env);
      }
      return env.result as T;
    } finally {
      clearTimeout(t);
    }
  }
'''

TS_FOOTER = "}\n"


def gen_ts(spec: dict) -> str:
    out = [TS_HEADER]
    out.append("\n  // ---------- typed methods (auto-generated) ----------\n")
    for m in spec["methods"]:
        name = m["name"]
        ts_name = to_camel(name)
        params = m.get("params", [])
        summary = m.get("summary", "").replace("*/", "* /")
        if summary:
            out.append(f"  /** {summary} */\n")
        if not params:
            out.append(
                f'  async {ts_name}(): Promise<any> {{\n'
                f'    return this.call({json.dumps(name)});\n'
                f'  }}\n\n'
            )
        else:
            out.append(
                f'  async {ts_name}(params?: object): Promise<any> {{\n'
                f'    return this.call({json.dumps(name)}, params);\n'
                f'  }}\n\n'
            )
    out.append(TS_FOOTER)
    return "".join(out)


# ----- entry -----

def main() -> int:
    if not SPEC_PATH.exists():
        print(f"error: spec not found at {SPEC_PATH}", file=sys.stderr)
        print("run: cd mod-c && nix develop .. --command ./gradlew generateOpenRpc", file=sys.stderr)
        return 1
    spec = json.loads(SPEC_PATH.read_text())
    n = len(spec.get("methods", []))

    GO_DIR.mkdir(parents=True, exist_ok=True)
    PY_DIR.mkdir(parents=True, exist_ok=True)
    TS_DIR.mkdir(parents=True, exist_ok=True)

    py = gen_python(spec)
    (PY_DIR / "btone_client.py").write_text(py)

    go = gen_go(spec)
    (GO_DIR / "btone.go").write_text(go)

    ts = gen_ts(spec)
    (TS_DIR / "btone_client.ts").write_text(ts)

    # Also drop a quick README in each
    (GO_DIR / ".." / ".." / "README.md").write_text(
        "# btone clients\n\n"
        "Auto-generated from `proto/btone-openrpc.json`. Do not edit by hand.\n\n"
        "Re-generate with `python3 bin/generate-clients.py`.\n"
    )
    print(f"wrote {n} methods to:", file=sys.stderr)
    print(f"  - {PY_DIR / 'btone_client.py'}", file=sys.stderr)
    print(f"  - {GO_DIR / 'btone.go'}", file=sys.stderr)
    print(f"  - {TS_DIR / 'btone_client.ts'}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
