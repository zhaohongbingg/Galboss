#!/usr/bin/env python3
"""GeckoLib 动画自检：把"静默失效"的问题在编码阶段拦下来。

用法:
    python check_animation.py <geo.json> <animation.json> [更多 animation.json ...]
                              [--fps N] [--strict] [--all]

为什么需要它:
    GeckoLib 里动画引用的骨骼名写错**不会报错** —— 动画照播，那根骨骼就是不动。
    关键帧时间越出动画长度、循环动画首尾不一致、在空骨骼上打关键帧也一样是静默的。
    这些只能靠静态检查兜住。

检查项（错误 = 一定有问题）:
    1. 动画引用了 geo 里不存在的骨骼名
    2. 关键帧时间越出 [0, animation_length]
    3. loop=true 的动画首尾姿态不一致 / 缺 t=0 或 t=len（旋转按 360° 取模比较，
       所以 -720 与 0 视为相同）
    4. 在"死骨头"上打关键帧（既没有立方体、也没有子骨骼 —— 转了没有任何效果）
    5. 关键帧的值不是 3 个数字
    6. 同名动画在不同 animation.json 里时长不一致（Java 侧常按动画名算 tick，会与判定错位）

风格建议（默认只是提示，--strict 下算错误）:
    7. 关键帧时间不在项目 fps 的整帧上（导出会被吸附，源码与产物对不上）
    8. 动画名不规范（含空格、纯数字、同义异名）
    9. 缓动曲线种类过多
"""

import json
import sys
from collections import Counter, defaultdict

MAX_EASINGS = 5
CANDIDATE_FPS = (12, 16, 20, 24, 25, 30, 48, 60)
TIME_TOL = 1e-6
ROT_MOD = 360.0

SYNONYM_GROUPS = [
    {"death", "dead", "despawn", "die"},
    {"idle", "wait", "stand", "idle_loop"},
    {"walk", "walking", "run_slow"},
    {"run", "sprint", "running"},
    {"attack", "hit", "strike", "melee"},
    {"hurt", "damage", "hit_taken"},
]


def short(path):
    return str(path).replace("\\", "/").split("/")[-1]


def load(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def geometry_root(geo):
    """兼容 minecraft:geometry 是数组 / 对象两种写法。"""
    g = geo.get("minecraft:geometry", geo.get("geometry"))
    if g is None:
        raise SystemExit("geo 里找不到 minecraft:geometry")
    return g[0] if isinstance(g, list) else g


def walk_bones(bones, out, parent=None):
    for b in bones or []:
        out.append({"name": b.get("name"), "parent": b.get("parent", parent),
                    "cubes": len(b.get("cubes") or [])})
        walk_bones(b.get("children"), out, b.get("name"))
    return out


def analyse_rig(geo):
    g = geometry_root(geo)
    bones = walk_bones(g.get("bones"), [])
    with_cubes = {b["name"] for b in bones if b["cubes"] > 0}
    has_child = {b["parent"] for b in bones if b["parent"]}
    return {
        "bones": bones,
        "by_name": {b["name"]: b for b in bones},
        "with_cubes": with_cubes,
        # 死骨头：既没有立方体又没有子骨骼，动画驱动它没有任何效果
        "dead": {b["name"] for b in bones if b["name"] not in with_cubes and b["name"] not in has_child},
        "roots": [b["name"] for b in bones if not b["parent"]],
    }


def value_of(val):
    """取出一个关键帧的值。三种写法都要认：

        [x, y, z]                                裸数组
        {"vector": [x, y, z]}                    GeckoLib 简写
        {"pre": [...], "post": [x,y,z], ...}     Blockbench 的贝塞尔手柄形式（取 post）

    返回 (值, 是否是 Molang 表达式)。
    """
    if isinstance(val, list):
        return val, any(isinstance(x, str) for x in val)
    if isinstance(val, dict):
        for key in ("vector", "post", "pre"):
            if key in val and isinstance(val[key], list):
                return val[key], any(isinstance(x, str) for x in val[key])
    return None, False


def channel_keys(ch):
    """归一化成 [(通道名, [(时间, 值, 是否Molang), ...])]；恒定值（无时间键）时间记 None。"""
    out = []
    for name in ("rotation", "position", "scale"):
        v = ch.get(name)
        if not isinstance(v, dict):
            continue
        if len(v) == 1 and "vector" in v:
            value, molang = value_of(v["vector"])
            out.append((name, [(None, value, molang)]))
            continue
        pairs = []
        for key, val in v.items():
            try:
                t = float(key)
            except (TypeError, ValueError):
                continue
            value, molang = value_of(val)
            pairs.append((t, value, molang))
        if pairs:
            pairs.sort(key=lambda p: p[0])
            out.append((name, pairs))
    return out


def same_pose(chan, a, b):
    """比较两帧姿态。旋转按 360° 取模 —— 转 -720 和转 0 看着是一回事。"""
    if a is None or b is None:
        return a == b
    if len(a) != len(b):
        return False
    for x, y in zip(a, b):
        if isinstance(x, str) or isinstance(y, str):
            if x != y:
                return False
        elif chan == "rotation":
            if abs((float(x) - float(y)) % ROT_MOD) > 1e-4 and \
               abs((float(x) - float(y)) % ROT_MOD - ROT_MOD) > 1e-4:
                return False
        elif abs(float(x) - float(y)) > 1e-4:
            return False
    return True


def detect_fps(times, forced):
    if forced:
        return float(forced), 1.0
    if not times:
        return 24.0, 1.0
    best, best_rate = 24.0, -1.0
    for fps in CANDIDATE_FPS:
        hit = sum(1 for t in times if abs(t * fps - round(t * fps)) < 0.005)
        rate = hit / len(times)
        if rate > best_rate + 1e-9:
            best, best_rate = float(fps), rate
    return best, best_rate


class Report:
    def __init__(self):
        self.errors = []
        self.notes = []
        self.meta = {
            "files": [], "loops": Counter(), "lengths": [], "channels": Counter(),
            "molang": 0, "easings": Counter(), "used_bones": set(), "times": [],
            "length_by_name": defaultdict(list), "keys_per_anim": [],
            "format_versions": set(), "glossed": [], "frames": [],
        }

    def add(self, bucket, path, name, cat, msg):
        bucket.append(("%s|%s|%s" % (short(path), name, cat), "[%s] %s: %s" % (short(path), name, msg)))

    def err(self, path, name, msg, cat="?"):
        self.add(self.errors, path, name, cat, msg)

    def note(self, path, name, msg, cat="?"):
        self.add(self.notes, path, name, cat, msg)

    @staticmethod
    def dump(items, limit, show_all):
        """同一类问题只打 limit 条；完全相同的消息只打一次（同一时间会同时出现在
        rotation 与 position 上，不去重会把输出刷满）。"""
        seen = Counter()
        printed = set()
        for key, line in items:
            cat = key.split("|")[-1]
            if line in printed:
                continue
            printed.add(line)
            seen[cat] += 1
            if not show_all and seen[cat] > limit:
                continue
            print("  " + line)
        for cat, n in seen.items():
            if not show_all and n > limit:
                print("  ... 同类（%s）还有 %d 条" % (cat, n - limit))


def main(argv):
    strict = "--strict" in argv
    show_all = "--all" in argv
    fps = None
    if "--fps" in argv:
        fps = argv[argv.index("--fps") + 1]
    args = [a for a in argv if not a.startswith("--")]
    if fps is not None:
        args = [a for a in args if a != fps]
    if len(args) < 2:
        print(__doc__)
        return 2
    geo_path, anim_paths = args[0], args[1:]

    rig = analyse_rig(load(geo_path))
    report = Report()

    for path in anim_paths:
        doc = load(path)
        anims = doc.get("animations") or {}
        report.meta["files"].append((path, len(anims)))
        report.meta["format_versions"].add(str(doc.get("format_version")))
        if not anims:
            report.err(path, "-", "文件里没有 animations", "空文件")
        if "geckolib_format_version" not in doc:
            report.note(path, "-", '缺 geckolib_format_version（GeckoLib 4.x 导出通常带 "2"）', "版本字段")

        for name, a in anims.items():
            short_name = name.split(".")[-1]
            length = a.get("animation_length")
            loop = a.get("loop")
            bones = a.get("bones") or {}
            report.meta["loops"][str(loop)] += 1
            if isinstance(length, (int, float)):
                report.meta["lengths"].append(length)
            report.meta["keys_per_anim"].append(sum(len(k) for k in bones.values()))
            report.meta["length_by_name"][short_name].append((path, length))

            if isinstance(loop, str) and loop not in ("hold_on_last_frame", "true", "false"):
                report.err(path, name, "loop 取值 %r 不合法" % loop, "loop 取值")

            # ---- 8. 命名规范 ----
            if " " in short_name:
                report.note(path, name, "动画名里有空格，容易拼错", "命名规范")
            if short_name.isdigit():
                report.note(path, name, "动画名是纯数字，含义不明", "命名规范")
            for grp in SYNONYM_GROUPS:
                both = grp & {k.split(".")[-1] for k in anims}
                if short_name in both and len(both) > 1:
                    report.note(path, name, "同义词并存: " + ", ".join(sorted(both)), "命名规范")
                    break

            for bone, ch in bones.items():
                report.meta["used_bones"].add(bone)
                # ---- 1. 骨骼名 ----
                if bone not in rig["by_name"]:
                    report.err(path, name, "引用了不存在的骨骼 %r（GeckoLib 不会报错，这根本不会动）"
                               % bone, "骨骼名不存在")
                    continue
                # ---- 4. 死骨头 ----
                if bone in rig["dead"]:
                    report.err(path, name,
                               "%s 既没有立方体也没有子骨骼，在这上面打关键帧不会有任何效果" % bone,
                               "死骨头")

                for chan, pairs in channel_keys(ch):
                    report.meta["channels"][chan] += 1
                    for t, val, molang in pairs:
                        if molang:
                            report.meta["molang"] += 1
                            continue
                        if val is None:
                            report.err(path, name, "%s.%s 的关键帧取不到 3 个分量（认不出写法）"
                                       % (bone, chan), "关键帧写法")
                            continue
                        if len(val) != 3:
                            report.err(path, name, "%s.%s 的值必须是 3 个数字，实际 %r"
                                       % (bone, chan, val), "关键帧写法")
                            continue
                        if t is None:
                            continue
                        report.meta["times"].append(t)
                        report.meta["frames"].append((path, name, chan, t))
                        # ---- 2. 时间范围 ----
                        if length is not None and t > length + TIME_TOL:
                            report.err(path, name,
                                       "%s.%s 关键帧 %.4f 越出 animation_length %.4f"
                                       % (bone, chan, t, length), "时间越界")
                    # ---- 3. 循环首尾 ----
                    timed = [p for p in pairs if p[0] is not None]
                    if loop is True and len(timed) > 1 and length is not None:
                        if timed[0][0] != 0:
                            report.err(path, name, "循环动画 %s.%s 缺 t=0 关键帧" % (bone, chan),
                                       "循环缺首帧")
                        if abs(timed[-1][0] - length) > TIME_TOL:
                            report.err(path, name,
                                       "循环动画 %s.%s 缺 t=%.4f 关键帧（末帧在 %.4f）"
                                       % (bone, chan, length, timed[-1][0]), "循环缺末帧")
                        elif not same_pose(chan, timed[0][1], timed[-1][1]):
                            report.err(path, name, "循环动画 %s.%s 首尾不一致: %r -> %r"
                                       % (bone, chan, timed[0][1], timed[-1][1]), "循环首尾不一致")

                # ---- 9. 缓动 ----
                for which in ("rotation", "position", "scale"):
                    v = ch.get(which)
                    if not isinstance(v, dict):
                        continue
                    for _k, val in v.items():
                        if isinstance(val, dict):
                            if "easing" in val:
                                report.meta["easings"][str(val["easing"])] += 1
                            if "lerp_mode" in val:
                                report.meta["easings"]["lerp:" + str(val["lerp_mode"])] += 1

    used_fps, rate = detect_fps(report.meta["times"], fps)
    report.meta["fps"] = used_fps
    report.meta["fps_rate"] = rate
    # ---- 7. 帧网格（fps 自动探测；只有"大多数时间确实落在某个 fps 上"才报）----
    if report.meta["times"] and rate >= 0.8:
        for path, name, chan, t in report.meta["frames"]:
            if abs(t * used_fps - round(t * used_fps)) > 0.005:
                report.note(path, name,
                            "%s 的时间 %.4f 不在 %g fps 的整帧上（导出会吸附到 %g 帧 = %.4f）"
                            % (chan, t, used_fps, round(t * used_fps), round(t * used_fps) / used_fps),
                            "帧网格")

    # ---- 6. 跨文件同名动画时长 ----
    for short_name, entries in sorted(report.meta["length_by_name"].items()):
        files = {short(p) for p, _ in entries}
        lens = {l for _, l in entries if l is not None}
        if len(files) > 1 and len(lens) > 1:
            report.err("<多文件>", short_name,
                       "同名动画在不同文件里时长不一致 %s（Java 侧常按动画名算 tick，会与判定错位）"
                       % sorted(lens), "时长不一致")

    if len(report.meta["easings"]) > MAX_EASINGS:
        report.note("<全局>", "-", "用了 %d 种缓动曲线（%s），建议统一到 %d 种以内"
                    % (len(report.meta["easings"]), ", ".join(sorted(report.meta["easings"])),
                       MAX_EASINGS), "缓动种类")

    # ---------------------------------------------------------------- 输出
    m = report.meta
    print("=" * 72)
    print("骨架: %d 根骨骼 / %d 根带立方体 / %d 根死骨头 / 顶层 %s"
          % (len(rig["bones"]), len(rig["with_cubes"]), len(rig["dead"]), rig["roots"]))
    if rig["dead"]:
        print("      死骨头(别在上面打关键帧): %s" % ", ".join(sorted(rig["dead"])))
    print("版本: format_version=%s" % ", ".join(sorted(m["format_versions"])))
    for path, n in m["files"]:
        print("动画: %s -> %d 段" % (short(path), n))
    if m["keys_per_anim"]:
        print("合计: %d 段 / %d 个关键帧 / 平均 %.1f"
              % (sum(n for _, n in m["files"]), sum(m["keys_per_anim"]),
                 sum(m["keys_per_anim"]) / len(m["keys_per_anim"])))
    print("loop 分布: %s" % dict(m["loops"]))
    if m["lengths"]:
        ls = sorted(m["lengths"])
        q = lambda p: ls[min(len(ls) - 1, int(len(ls) * p))]
        print("时长(秒): min=%.4f p25=%.4f p50=%.4f p95=%.4f max=%.4f"
              % (ls[0], q(.25), q(.5), q(.95), ls[-1]))
    print("通道使用: %s" % dict(m["channels"]))
    print("Molang 值: %d 处" % m["molang"])
    print("缓动种类: %d 种" % len(m["easings"]))
    if m["times"]:
        print("帧网格: 探测到 %g fps（%.0f%% 的时间落在整帧上）%s"
              % (m["fps"], m["fps_rate"] * 100,
                 "" if m["fps_rate"] >= 0.8 else " ← 时间不成网格，可能是手写或混了多套 fps"))
    unused = sorted(set(rig["by_name"]) - m["used_bones"] - rig["dead"])
    if unused:
        print("未被任何动画使用的骨骼: %s" % ", ".join(unused))
    print("=" * 72)

    if report.errors:
        cats = Counter(k.split("|")[-1] for k, _ in report.errors)
        print("\n错误 %d 条 %s" % (len(report.errors), dict(cats)))
        Report.dump(report.errors, 8, show_all)
    if report.notes:
        cats = Counter(k.split("|")[-1] for k, _ in report.notes)
        print("\n%s %d 条 %s" % ("错误" if strict else "提示", len(report.notes), dict(cats)))
        Report.dump(report.notes, 8, show_all)

    bad = len(report.errors) + (len(report.notes) if strict else 0)
    print("\n%s" % ("通过" if not bad else "%d 个问题%s" % (bad, "" if strict else "（提示不计入）")))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
