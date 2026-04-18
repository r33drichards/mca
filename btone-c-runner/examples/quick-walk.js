// Walks the player ~30 blocks NE via Baritone, polling status until idle.
//
// To run via mcp-v8: have the agent submit this script to the `run_js` tool.
// PORT and TOKEN are read from `~/.../config/btone-bridge.json` -- the mod
// writes that file at startup. mcp-v8's `fs` module is policy-gated by
// default, so for ad-hoc testing the simplest thing is to paste the values
// from the bridge file into the prompt the agent gets.

const PORT = 25591;
const TOKEN = "REPLACE_WITH_TOKEN_FROM_btone-bridge.json";

async function rpc(method, params = {}) {
    const r = await fetch(`http://127.0.0.1:${PORT}/rpc`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Authorization": `Bearer ${TOKEN}`,
        },
        body: JSON.stringify({ method, params }),
    });
    return r.json();
}

const state = await rpc("player.state");
if (!state.ok || !state.result.inWorld) {
    return JSON.stringify({ error: "not in world", state });
}

const start = state.result.pos;
console.log("player at", start);

const goal = { x: Math.round(start.x) + 30, z: Math.round(start.z) + 30 };
const goResp = await rpc("baritone.goto", goal);
console.log("goto", goResp);

let last = null;
for (let i = 0; i < 60; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const s = await rpc("baritone.status");
    last = s.result;
    console.log("tick", i, last);
    if (s.ok && !last.active) break;
}

const final = await rpc("player.state");
return JSON.stringify({
    start,
    goal,
    finalPos: final.result?.pos ?? null,
    lastStatus: last,
});
