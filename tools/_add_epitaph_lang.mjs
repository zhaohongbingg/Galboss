import { readFileSync, writeFileSync } from 'node:fs';
const files = [
  ['src/main/resources/assets/galboss/lang/zh_cn.json', '序章 · 碑文开始'],
  ['src/main/resources/assets/galboss/lang/en_us.json', 'Prologue · The Epitaph Begins'],
];
const KEY = 'epitaph.galboss.night_name.0';
for (const [path, value] of files) {
  let t = readFileSync(path, 'utf8');
  if (t.includes('"' + KEY + '"')) {
    console.log('already present:', path);
    continue;
  }
  const anchor = '  "epitaph.galboss.night_name.1"';
  const idx = t.indexOf(anchor);
  if (idx < 0) throw new Error('anchor missing in ' + path);
  const ins = '  "' + KEY + '": ' + JSON.stringify(value) + ',\n';
  t = t.slice(0, idx) + ins + t.slice(idx);
  writeFileSync(path, t);
  console.log('added to', path, '->', value);
}
for (const [path] of files) {
  JSON.parse(readFileSync(path, 'utf8'));
}
console.log('lang JSON still valid');
