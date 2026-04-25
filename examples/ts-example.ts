// Example: drive btone-mod-c with the generated TypeScript client.
//
// Run with:
//   cd clients/typescript && ./node_modules/.bin/tsx ../../examples/ts-example.ts
import { writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { BtoneClient } from "../clients/typescript/src/btone_client.ts";

async function main(): Promise<void> {
  const bot = new BtoneClient();
  console.log("=== ts example ===");

  // 1. Self-introspect
  const spec: any = await bot.rpcDiscover();
  console.log(`discovered ${spec.methods.length} methods`);

  // 2. Read player state
  const state: any = await bot.playerState();
  console.log(JSON.stringify({
    inWorld: state.inWorld,
    name: state.name,
    hp: state.health,
    food: state.food,
    pos: state.blockPos,
  }));

  // 3. Inventory snippet
  const inv: any = await bot.playerInventory();
  console.log(JSON.stringify(inv.main.slice(0, 3)));

  // 4. Screenshot
  const shot: any = await bot.worldScreenshot({ width: 480, yaw: 180, pitch: -5 });
  const buf = Buffer.from(shot.image, "base64");
  const out = join(tmpdir(), "btone-ts-shot.png");
  writeFileSync(out, buf);
  console.log(`wrote ${buf.length} bytes to ${out}`);

  // 5. Low-level call() works for ad-hoc methods
  const fullSpec: any = await bot.call("rpc.discover");
  const baritone = fullSpec.methods
    .filter((m: any) => m.name.startsWith("baritone."))
    .map((m: any) => m.name);
  console.log(`baritone.* methods: ${JSON.stringify(baritone)}`);

  console.log("OK");
}

main().catch(e => {
  console.error("error:", e);
  process.exit(1);
});
