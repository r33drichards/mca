import { BtoneClient } from "./btone_client.js";

const bot = new BtoneClient();
const spec: any = await bot.rpcDiscover();
console.log("ts: rpc.discover ok, methods=", spec.methods.length);
const state: any = await bot.playerState();
console.log("ts: player.state inWorld=", state.inWorld);
