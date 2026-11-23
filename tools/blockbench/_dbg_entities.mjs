import { forEachChunk } from "./world_lib.mjs";
let chunks = 0, withEntities = 0, total = 0; const sample = [];
forEachChunk("D:/game/mc/bossmod/run/world", (nbt) => {
  chunks++;
  const es = nbt.entities || [];
  if (es.length) { withEntities++; total += es.length; for (const e of es) if (sample.length < 8) sample.push(e.id + " @ " + (e.Pos||[]).map(v=>Number(v.toFixed?.(1)??v)).join(",")); }
});
console.log("chunks", chunks, "withEntities", withEntities, "totalEntities", total);
console.log(sample.join("\n"));
