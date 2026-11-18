import fs from "node:fs";
const a=JSON.parse(fs.readFileSync("../../src/main/resources/assets/galboss/animations/black_goat_servant.animation.json","utf8"));
for(const [k,v] of Object.entries(a.animations||{})){
  console.log(k, "len="+v.animation_length, "loop="+JSON.stringify(v.loop), "bones="+Object.keys(v.bones||{}).join(","));
}
