#!/usr/bin/env node
// Tiny stdio<->Streamable-HTTP MCP proxy for btone-mod-b.
// Reads line-delimited JSON-RPC from stdin, POSTs each line verbatim to
// http://127.0.0.1:<port>/mcp with the bearer token from btone-bridge.json,
// writes the HTTP response body + "\n" back to stdout.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const cfgPath = process.env.BTONE_CONFIG ?? findConfig();
if (!cfgPath || !fs.existsSync(cfgPath)) {
  die(`btone-bridge.json not found (looked at ${cfgPath ?? '<none>'}); set BTONE_CONFIG to override.`);
}
let port, token;
try {
  ({ port, token } = JSON.parse(fs.readFileSync(cfgPath, 'utf8')));
} catch (e) {
  die(`failed to parse ${cfgPath}: ${e.message}`);
}
if (!port || !token) die(`btone-bridge.json missing port/token (${cfgPath})`);
const url = `http://127.0.0.1:${port}/mcp`;

function configRoots() {
  const home = os.homedir();
  const roots = [];
  if (process.platform === 'darwin') {
    roots.push(path.join(home, 'Library/Application Support/PrismLauncher/instances'));
  } else if (process.platform === 'linux') {
    roots.push(path.join(home, '.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances'));
    roots.push(path.join(home, '.local/share/PrismLauncher/instances'));
  } else if (process.platform === 'win32' && process.env.APPDATA) {
    roots.push(path.join(process.env.APPDATA, 'PrismLauncher/instances'));
  }
  return roots;
}

function findConfig() {
  let best = null;
  let bestMtime = -Infinity;
  for (const root of configRoots()) {
    if (!fs.existsSync(root)) continue;
    let entries;
    try { entries = fs.readdirSync(root); } catch { continue; }
    for (const inst of entries) {
      const candidates = [
        path.join(root, inst, '.minecraft/config/btone-bridge.json'),
        path.join(root, inst, 'minecraft/config/btone-bridge.json'),
      ];
      for (const c of candidates) {
        let st;
        try { st = fs.statSync(c); } catch { continue; }
        if (st.mtimeMs > bestMtime) { best = c; bestMtime = st.mtimeMs; }
      }
    }
  }
  return best;
}

function die(msg) {
  process.stderr.write(`btone-mcp-stdio: ${msg}\n`);
  process.exit(1);
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf('\n')) !== -1) {
    const line = buf.slice(0, nl);
    buf = buf.slice(nl + 1);
    const trimmed = line.trim();
    if (!trimmed) continue;
    forward(trimmed);
  }
});
process.stdin.on('end', () => process.exit(0));

async function forward(line) {
  let parsedId = null;
  try { parsedId = JSON.parse(line)?.id ?? null; } catch {}
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: line,
    });
    const text = await r.text();
    process.stdout.write(text + '\n');
  } catch (e) {
    const errResp = {
      jsonrpc: '2.0',
      id: parsedId,
      error: { code: -32603, message: `proxy fetch failed: ${e.message ?? String(e)}` },
    };
    process.stdout.write(JSON.stringify(errResp) + '\n');
  }
}
