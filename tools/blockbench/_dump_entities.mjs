import fs from "node:fs";
import zlib from "node:zlib";
import { R } from "./world_lib.mjs";
const f = process.argv[2];
const t = new R(zlib.gunzipSync(fs.readFileSync(f)), true).root();
for (const e of t.entities?.v || []) {
  const nbt = e.v.nbt.v;
  console.log("entity");
  console.log("  pos:", e.v.pos.v.map(p => p.v).join(", "));
  console.log("  blockPos:", e.v.blockPos?.v?.map(p => p.v).join(", "));
  for (const k of Object.keys(nbt)) console.log(`  ${k}: t=${nbt[k].t} ${JSON.stringify(nbt[k].v).slice(0, 70)}`);
}
