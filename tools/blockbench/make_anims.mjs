import { initialize, callTool } from './_mcp_client.mjs';
import { animsFor, overriddenNames } from './anims.mjs';

const textOf = (res) => {
  const c = res?.result?.content;
  return Array.isArray(c) ? c.map((x) => x.text ?? JSON.stringify(x)).join('\n') : JSON.stringify(res);
};

await initialize();

// 1. 清空当前项目里的动画，拿到项目名以推导动画前缀
const cleared = await callTool('risky_eval', {
  code: `(function () {
    var removed = Animation.all.map(function (a) { return a.name; });
    Animation.all.slice().forEach(function (a) { a.remove(); });
    Canvas.updateAll();
    return { project: Project.name, removed: removed };
  })()`
});
console.log(textOf(cleared));

const projectName = (textOf(cleared).match(/"project":"([a-z_0-9]+)"/) || [])[1];
if (!projectName) {
  console.error('无法确定当前项目名，中止');
  process.exit(1);
}
// Blockbench 会按格式自动补上 "animation." 前缀，所以这里只给 项目名.动画名
const prefix = `${projectName}.`;
console.log(`动画名前缀(不含自动添加的 animation.): ${prefix}`);

// 该角色的动画集 = 基础版 + 专属覆盖（见 anims.mjs 的 CHARACTER_ANIMS）
const list = animsFor(projectName);
const overridden = overriddenNames(projectName);
console.log(`动画 ${list.length} 段，其中 ${overridden.length} 段是该角色专属: ${overridden.join(', ') || '（无，用基础版）'}\n`);

// 2. 逐条建立动画
for (const a of list) {
  const res = await callTool('create_animation', {
    name: prefix + a.name,
    loop: !!a.loop,
    animation_length: a.len,
    bones: a.bones
  });
  const txt = textOf(res);
  if (res?.result?.isError) {
    console.log(`FAIL ${a.name}: ${txt}`);
  } else {
    console.log(`ok   ${a.name.padEnd(8)} len=${a.len}s loop=${!!a.loop} bones=${Object.keys(a.bones).join(',')}`);
  }
}

// 3. 回读确认
const back = await callTool('risky_eval', {
  code: `(function () {
    return Animation.all.map(function (a) {
      var bones = a.bones ? Object.keys(a.bones) : [];
      return a.name + ' len=' + a.animation_length + ' loop=' + a.loop + ' bones=' + bones.join('|');
    });
  })()`
});
console.log('\n回读:');
console.log(textOf(back));
