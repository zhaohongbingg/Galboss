import { AIR } from './world_lib.mjs';

// 结构导出时的「门」修正（export_structure.mjs 依赖本模块）。
//
// 背景：手搭存档里的门可能出现两类问题
//   1. 铰链（hinge）装反：开门那一侧没有墙，门板会悬空；
//   2. 门是开着的，结构里应该关着。
//
// 铰链方向的判定照原版 `DoorBlock#getHinge` 的几何规则：
//   - 门的状态里 facing 就是放置时玩家朝向（原版 setValue(FACING, context.getHorizontalDirection())）；
//   - 取 facing 的逆时针侧（getCounterClockWise）与顺时针侧（getClockWise），各看上下两半是否有**完整碰撞方块**；
//   - 有墙在逆时针侧 → hinge=LEFT；有墙在顺时针侧 → hinge=RIGHT；两侧都有/都没有 → 保持原样。
// 判定完整碰撞方块用「非空气且不在非完整方块名单里」近似 —— 原版用 isCollisionShapeFullBlock，
// 这里没有方块形状表，名单覆盖门/玻璃板/栅栏/花草/火把/地毯/按钮/告示牌等常见薄/无碰撞方块。
//
// 返回：{ total, flipped, ok, both, none, closed, mismatch }（export_structure 会打印这些统计）。

const NON_FULL = new Set([
  'air', 'cave_air', 'void_air', 'water', 'lava', 'fire', 'soul_fire',
  'short_grass', 'grass', 'fern', 'dead_bush', 'seagrass', 'tall_seagrass', 'kelp', 'kelp_plant',
  'dandelion', 'poppy', 'blue_orchid', 'allium', 'azure_bluet', 'oxeye_daisy', 'cornflower',
  'red_tulip', 'orange_tulip', 'white_tulip', 'pink_tulip', 'lily_of_the_valley',
  'torch', 'wall_torch', 'soul_torch', 'soul_wall_torch', 'lantern', 'soul_lantern',
  'redstone_torch', 'redstone_wall_torch', 'lever', 'tripwire', 'tripwire_hook',
  'glass_pane', 'iron_bars', 'fence', 'fence_gate', 'wall_sign', 'sign', 'hanging_sign',
  'redstone_wire', 'rail', 'ladder', 'vine', 'snow', 'carpet', 'moss_carpet',
  'button', 'pressure_plate', 'light', 'banner', 'wall_banner', 'lily_pad',
  'flower_pot', 'potted_fern', 'item_frame', 'painting',
]);

const DIRS = {
  north: { x: 0, z: -1 },
  south: { x: 0, z: 1 },
  east: { x: 1, z: 0 },
  west: { x: -1, z: 0 },
};
// Direction 的顺时针 / 逆时针（北→东→南→西）
const CW = { north: 'east', east: 'south', south: 'west', west: 'north' };
const CCW = { north: 'west', west: 'south', south: 'east', east: 'north' };

const baseName = (name) => name.includes(':') ? name.split(':')[1] : name;

function isFullBlock(entry) {
  if (!entry) return false;
  if (entry.Name === 'minecraft:air' || entry.Name === 'minecraft:cave_air' || entry.Name === 'minecraft:void_air') return false;
  const n = baseName(entry.Name);
  if (NON_FULL.has(n)) return false;
  if (n.endsWith('_door') || n.endsWith('_fence') || n.endsWith('_pane') || n.endsWith('_button') ||
      n.endsWith('_pressure_plate') || n.endsWith('_sign') || n.endsWith('_banner') ||
      n.endsWith('_carpet') || n.endsWith('_torch') || n.endsWith('_sapling')) return false;
  return true;
}

export function fixDoors(blocks, palette, palIndexFor) {
  const stats = { total: 0, flipped: 0, ok: 0, both: 0, none: 0, closed: 0, mismatch: 0 };

  // 位置 -> block（结构内坐标）
  const at = new Map();
  for (const b of blocks) at.set(`${b.x},${b.y},${b.z}`, b);
  const entryOf = (block) => palette[block.state];

  const isDoor = (entry) => entry && baseName(entry.Name).endsWith('_door') && entry.Properties && entry.Properties.half;

  const doors = blocks.filter(b => isDoor(entryOf(b)));
  const seenLower = new Set();

  for (const lower of doors) {
    const e = entryOf(lower);
    if (e.Properties.half !== 'lower') continue;
    const key = `${lower.x},${lower.z}`;
    if (seenLower.has(key)) continue;
    seenLower.add(key);
    stats.total++;

    const upper = at.get(`${lower.x},${lower.y + 1},${lower.z}`);
    const upperEntry = upper ? entryOf(upper) : null;
    const facing = (e.Properties.facing || 'north');
    const side = (dir) => {
      const d = DIRS[dir];
      const lowerN = at.get(`${lower.x + d.x},${lower.y},${lower.z + d.z}`);
      const upperN = at.get(`${lower.x + d.x},${lower.y + 1},${lower.z + d.z}`);
      return isFullBlock(lowerN ? entryOf(lowerN) : null) && isFullBlock(upperN ? entryOf(upperN) : null);
    };
    const ccwWall = side(CCW[facing]);
    const cwWall = side(CW[facing]);

    let expected = null;
    if (ccwWall && !cwWall) expected = 'left';
    else if (cwWall && !ccwWall) expected = 'right';
    else if (ccwWall && cwWall) stats.both++;
    else stats.none++;

    const cur = e.Properties.hinge || 'left';
    if (expected && expected !== cur) {
      // 换铰链 + 关门：两个半块一起改，用新 palette 条目
      const mk = (baseEntry, hinge) => {
        const props = { ...baseEntry.Properties, hinge, open: 'false' };
        const idx = palIndexFor({ Name: baseEntry.Name, Properties: props });
        return { state: idx };
      };
      const loIdx = mk(e, expected);
      lower.state = loIdx.state;
      if (upperEntry) {
        const upIdx = mk(upperEntry, expected);
        if (upper) upper.state = upIdx.state;
        if (upperEntry.Properties.hinge !== cur) stats.mismatch++;
      }
      stats.flipped++;
      if (e.Properties.open === 'true') stats.closed++;
      continue;
    }

    // 铰链方向没问题，但仍要关门（上下半一致）
    if (e.Properties.open === 'true' || (upperEntry && upperEntry.Properties.open === 'true')) {
      const mkClosed = (baseEntry) => {
        const props = { ...baseEntry.Properties, open: 'false' };
        return palIndexFor({ Name: baseEntry.Name, Properties: props });
      };
      lower.state = mkClosed(e);
      if (upper && upperEntry) upper.state = mkClosed(upperEntry);
      stats.closed++;
    }
    if (upperEntry && upperEntry.Properties.hinge !== cur) {
      stats.mismatch++;
      const props = { ...upperEntry.Properties, hinge: cur };
      if (upper) upper.state = palIndexFor({ Name: upperEntry.Name, Properties: props });
    }
    stats.ok++;
  }

  return stats;
}
