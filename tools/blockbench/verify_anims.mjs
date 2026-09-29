import fs from 'node:fs';
import path from 'node:path';
import { initialize, callTool } from './_mcp_client.mjs';

// 从脚本自身位置推导，不写死盘符（原因见 pipeline.mjs 顶部说明）。
const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const textOf = (res) => {
  const c = res?.result?.content;
  return Array.isArray(c) ? c.map((x) => x.text ?? JSON.stringify(x)).join('\n') : JSON.stringify(res);
};

const CAM_QUARTER = { position: [48, 22, -62], target: [0, 17, 0], projection: 'perspective', fov: 32 };
const CAM_SIDE = { position: [78, 20, -6], target: [0, 17, 0], projection: 'perspective', fov: 32 };

// [动画名, 采样时间, 相机, 文件名后缀]
const SHOTS = [
  ['attack', 0.42, CAM_QUARTER, ''],
  ['attack', 0.42, CAM_SIDE, '_side'],
  ['cast', 0.5, CAM_QUARTER, ''],
  ['cast', 0.5, CAM_SIDE, '_side'],
  ['phase', 0.7, CAM_SIDE, '_side'],
  ['charge', 0.4, CAM_QUARTER, ''],
  ['charge', 0.4, CAM_SIDE, '_side'],
  ['dash', 0.3, CAM_QUARTER, ''],
  ['dash', 0.3, CAM_SIDE, '_side'],
  ['shoot', 0.15, CAM_QUARTER, ''],
  ['shoot', 0.15, CAM_SIDE, '_side'],
  ['guard', 0.44, CAM_QUARTER, ''],
  ['guard', 0.44, CAM_SIDE, '_side'],
  ['combo', 0.3, CAM_QUARTER, ''],
  ['rage', 0.45, CAM_QUARTER, ''],
  ['phase', 0.7, CAM_QUARTER, ''],
  // 三人有各自的专属版本（桐香空手 / 礼持枪 / 郁子持刀），这几张专门看差异：
  // shoot 的"握住"那一拍、combo 的第一下命中（T(7)=0.2917）、cast 的保持段。
  ['shoot', 0.25, CAM_QUARTER, '_hold'],
  ['shoot', 0.25, CAM_SIDE, '_hold_side'],
  ['combo', 0.29, CAM_QUARTER, '_hit'],
  ['cast', 0.45, CAM_QUARTER, '_hold']
];

await initialize();

// --char <id>：先切到指定角色的项目再渲染（三人各有专属动画，图要分开看）
const charArg = process.argv.indexOf('--char');
if (charArg >= 0) {
  const want = process.argv[charArg + 1];
  const sel = textOf(await callTool('risky_eval', {
    code: `(function () {
      var hit = null;
      ModelProject.all.forEach(function (p) { if (p.name === ${JSON.stringify(want)}) hit = p; });
      if (!hit) return { error: ${JSON.stringify(want)} };
      hit.select();
      return { active: Project.name };
    })()`
  }));
  console.log(sel);
}

const proj = (textOf(await callTool('get_project_info', {})).match(/"name":\s*"([a-z_0-9]+)"/) || [])[1];
const prefix = `animation.${proj}.`;
console.log(`project=${proj} prefix=${prefix}\n`);

const viewId = 'anim_check';
await callTool('delete_offscreen_view', { view: viewId }).catch(() => {});
await callTool('create_offscreen_view', { id: viewId, width: 420, height: 560, antialias: false, copy_view: 'none' });
let lastCam = null;
for (const [name, time, cam, suffix] of SHOTS) {
  if (cam !== lastCam) {
    await callTool('set_camera_angle', { view: viewId, ...cam });
    lastCam = cam;
  }
  const t = await callTool('animation_timeline', {
    animation_id: prefix + name, action: 'set_time', time
  });
  const info = textOf(t);
  const res = await callTool('capture_screenshot', { view: viewId });
  const img = (res?.result?.content || []).find((x) => x.type === 'image');
  if (img) {
    // 文件名带角色前缀：三人有各自的专属动画，不带前缀会互相覆盖
    fs.writeFileSync(`${HERE}/_preview/anim_${proj}_${name}${suffix}.png`, Buffer.from(img.data, 'base64'));
    console.log(`saved anim_${proj}_${name}${suffix}.png @${time}s`);
  } else {
    console.log(`failed ${name}${suffix}: ${JSON.stringify(res).slice(0, 200)} | timeline=${info.slice(0, 160)}`);
  }
}
await callTool('delete_offscreen_view', { view: viewId }).catch(() => {});
