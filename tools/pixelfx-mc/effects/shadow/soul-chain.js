import { Particles, Lights, Rng, fit, clamp, smooth, cycle, TAU, WHITE } from "../../core/index.js";

export const meta = {
  name: "Soul chain",
  desc: "A configurable-length, arbitrary-angle interlocking white-and-violet spectral chain binding two monsters: soul-locked anchor shackles at both endpoints, alternating 3D broadside and edge-on links with surging soul fire, and drifting wisps.",
  tags: ["shadow", "soul", "chain", "shackle", "tether", "link", "violet", "loop"],
  size: [240, 64],
  origin: [8, 32],
  duration: 1.0,
  loop: true,
  fps: 20,
  bg: "dark",
  options: {
    palette: ["#160824", "#2e114a", "#521d82", "#7d32bd", "#a85cf0", "#d39bff", "#f2dcff", "#ffffff"],
    length: 224, // 起点怪物 A 到终点怪物 B 的像素长度（默认 224，适配 240x64 画布）
    angle: 0, // 指向角度（弧度，0 = 水平向右）
    angleDeg: null, // 可选：指向角度（角度制，如 30、-45；传入时优先于 angle）
    dx: null, // 可选：终点相对起点的 X 像素偏移（与 dy 同时传入时自动计算长度与角度）
    dy: null, // 可选：终点相对起点的 Y 像素偏移
    seed: 5,
  },
};

const T_LOOP = meta.duration;
const WAVE_LEN = 120; // Low-frequency whole-chain vertical wave (wavelength 120 px)
const TARGET_PITCH = 16; // Physical center-to-center spacing per interlocking link

// Resolve chain length (px) and direction angle (rad) from options
function resolveChainSpec(o = {}) {
  if (typeof o.dx === "number" && typeof o.dy === "number") {
    const len = Math.max(40, Math.hypot(o.dx, o.dy));
    const ang = Math.atan2(o.dy, o.dx);
    return { len, ang };
  }
  const len = Math.max(40, typeof o.length === "number" ? o.length : 224);
  const ang =
    typeof o.angleDeg === "number"
      ? (o.angleDeg * Math.PI) / 180
      : typeof o.angle === "number"
        ? o.angle
        : 0;
  return { len, ang };
}

// Whole-chain perpendicular sway (+/- 2.6 px), pinned to ly = 0 at both monster endpoints (lx = 0 and lx = len)
function spineY(lx, len, phase) {
  const pinA = smooth(0, 20, lx);
  const pinB = 1 - smooth(len - 20, len, lx);
  return 2.6 * pinA * pinB * Math.sin((lx / WAVE_LEN) * TAU - phase);
}

// Energy surge intensity [0..1] flowing from target B (lx = len) to target A (lx = 0)
function pulseBoost(lx, len, uT) {
  let maxBoost = 0;
  const pulses = Math.max(2, Math.round(len / 45)); // Integer count guarantees exact loop closure at t = 1.0s
  const travel = Math.max(16, len - 8);
  const sigma2 = Math.max(64, Math.min(140, (travel / pulses) * 2.8));
  for (let i = 0; i < pulses; i++) {
    const f = (uT * pulses + i) % 1; // 0 at target B -> 1 at target A
    const px = len - 4 - f * travel;
    const dx = lx - px;
    const env = Math.sin(f * Math.PI);
    const g = Math.exp(-(dx * dx) / sigma2) * (0.35 + 0.65 * env);
    if (g > maxBoost) maxBoost = g;
  }
  return maxBoost;
}

// Shade in local chain space (lx along chain, ly perpendicular) mapped onto rotated world canvas
function shadeLocal(p, lx0, ly0, lx1, ly1, ca, sa, fn) {
  const corners = [
    [lx0 * ca - ly0 * sa, lx0 * sa + ly0 * ca],
    [lx1 * ca - ly0 * sa, lx1 * sa + ly0 * ca],
    [lx0 * ca - ly1 * sa, lx0 * sa + ly1 * ca],
    [lx1 * ca - ly1 * sa, lx1 * sa + ly1 * ca],
  ];
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const [cx, cy] of corners) {
    if (cx < minX) minX = cx;
    if (cx > maxX) maxX = cx;
    if (cy < minY) minY = cy;
    if (cy > maxY) maxY = cy;
  }
  p.shade(minX - 1, minY - 1, maxX + 1, maxY + 1, (wx, wy, out) => {
    const lx = wx * ca + wy * sa;
    const ly = -wx * sa + wy * ca;
    fn(lx, ly, out);
  });
}

// Evaluate a single interlocking chain link i in local chain coordinates (lx, ly)
function sampleLink(lx, ly, i, count, pitch, len, phase, uT, C) {
  if (i < 0 || i >= count) return null;
  const cx = 14 + (i + 0.5) * pitch;
  const cy = spineY(cx, len, phase);

  // Align link orientation with the local tangent of the swaying chain curve
  const dyDx = (spineY(cx + 2, len, phase) - spineY(cx - 2, len, phase)) * 0.25;
  const invLen = 1 / Math.hypot(1, dyDx);
  const tCa = invLen;
  const tSa = dyDx * invLen;

  const dx = lx - cx;
  const dy = ly - cy;
  const u = dx * tCa + dy * tSa;
  const v = -dx * tSa + dy * tCa;

  const boost = pulseBoost(cx, len, uT);
  const endFade = smooth(8, 15, cx) * (1 - smooth(len - 15, len - 8, cx));
  if (endFade <= 0.02) return null;

  // Consistent 3D weave: left half sits forward (z > 0), right half tucks under (z < 0)
  const z = u < 0 ? 1 : -1;
  const halfL = pitch * 0.69;

  if (i % 2 === 0) {
    // Broadside hollow stadium link (open center hole + bevelled metallic rim)
    const outerR = 6.0 + 0.65 * boost;
    const innerR = 2.6 + 0.25 * boost;
    const seg = Math.max(1, halfL - outerR);
    const d = Math.hypot(u - clamp(u, -seg, seg), v);
    if (d > outerR || d < innerR) return null;

    const rimDist = Math.min(outerR - d, d - innerR);
    let c;
    if (rimDist <= 0.9) {
      c = C[0];
    } else if (boost > 0.52 && rimDist > 1.35) {
      c = WHITE;
    } else if (boost > 0.28 && rimDist > 1.2) {
      c = C[6];
    } else if (v < -1.0) {
      c = rimDist > 1.45 ? C[6] : C[5];
    } else if (v > 1.2) {
      c = rimDist > 1.45 ? C[3] : C[2];
    } else {
      c = rimDist > 1.45 ? C[5] : C[4];
    }
    return { c, z, a: endFade };
  } else {
    // Edge-on interlocking link passing through neighboring hollow links
    const outerR = 2.55 + 0.55 * boost;
    const seg = Math.max(1, halfL - outerR);
    const d = Math.hypot(u - clamp(u, -seg, seg), v);
    if (d > outerR) return null;

    const rimDist = outerR - d;
    const inSeam = Math.abs(v) <= 0.55 && Math.abs(u) <= seg + 1.5;
    let c;
    if (rimDist <= 0.85) {
      c = C[0];
    } else if (inSeam) {
      c = boost > 0.45 ? C[5] : C[1];
    } else if (boost > 0.48) {
      c = WHITE;
    } else if (v < 0) {
      c = boost > 0.22 ? WHITE : C[6];
    } else {
      c = boost > 0.22 ? C[5] : C[3];
    }
    return { c, z, a: endFade };
  }
}

// Smooth tapered spectral wisp drawn in local chain coordinates and projected to world space
function drawLocalTendril(p, lx0, ly0, lx1, ly1, bend, r0, r1, C, phase, ca, sa) {
  const steps = Math.max(10, Math.ceil(Math.hypot(lx1 - lx0, ly1 - ly0) * 1.6));
  for (let i = 0; i <= steps; i++) {
    const u = i / steps;
    const wave = Math.sin(u * Math.PI) * bend + Math.sin(u * TAU + phase) * 0.9 * u;
    const lx = lx0 + (lx1 - lx0) * u;
    const ly = ly0 + (ly1 - ly0) * u + wave;
    const r = r0 + (r1 - r0) * u;
    const edgeFade = u > 0.8 ? 1 - (u - 0.8) / 0.2 : 1;
    const c = u > 0.65 ? (u > 0.85 ? WHITE : C[6]) : u < 0.35 ? C[4] : C[5];

    const wx = lx * ca - ly * sa;
    const wy = lx * sa + ly * ca;
    p.dot(wx, wy, c, 0.9 * edgeFade);
    if (r > 1.1) {
      const wxUp = lx * ca - (ly - 1) * sa;
      const wyUp = lx * sa + (ly - 1) * ca;
      const wxDn = lx * ca - (ly + 1) * sa;
      const wyDn = lx * sa + (ly + 1) * ca;
      p.dot(wxUp, wyUp, C[1], 0.62 * edgeFade);
      p.dot(wxDn, wyDn, C[3], 0.62 * edgeFade);
    }
  }
}

// Draw a soul-shackle ring latching onto an endpoint monster (center at local x = cx, dir = 1 start / -1 end)
function drawAnchorShackle(p, cx, dir, pulse, C, phase, ca, sa) {
  const wxC = cx * ca;
  const wyC = cx * sa;
  p.glow(wxC, wyC, 16, C[5], 0.48 + 0.32 * pulse);
  p.glow(wxC, wyC, 7.5, WHITE, 0.38 + 0.35 * pulse);

  // 4 outward-hooking ghostly wisps gripping the target
  drawLocalTendril(p, cx - dir * 3, -2, cx - dir * 9, -8, -2.0, 1.2, 0.35, C, phase, ca, sa);
  drawLocalTendril(p, cx - dir * 3, 2, cx - dir * 8, 8, 2.0, 1.2, 0.35, C, phase + 1.5, ca, sa);
  drawLocalTendril(p, cx + dir * 4, -5, cx + dir * 14, -10, -2.2, 1.3, 0.35, C, phase + 2.8, ca, sa);
  drawLocalTendril(p, cx + dir * 4, 5, cx + dir * 13, 10, 2.2, 1.3, 0.35, C, phase + 4.1, ca, sa);

  shadeLocal(p, cx - 10, -11, cx + 10, 11, ca, sa, (lx, ly, out) => {
    const d = Math.hypot(lx - cx, ly);
    const coreDist = Math.hypot(lx - (cx - dir * 0.8), ly);

    if (coreDist <= 2.3) {
      out.c = coreDist <= 1.15 ? WHITE : C[6];
      out.a = 1;
      return;
    }
    if (d >= 4.1 && d <= 8.4) {
      const ang = Math.atan2(ly, lx - cx);
      const glint = 0.5 + 0.5 * Math.cos(ang - phase * 2);
      if (d >= 7.5 || d <= 4.9) {
        out.c = C[0];
      } else if (pulse > 0.45 || glint > 0.84) {
        out.c = glint > 0.9 || pulse > 0.65 ? WHITE : C[6];
      } else if (ly < 0) {
        out.c = C[5];
      } else {
        out.c = C[3];
      }
      out.a = 1;
    }
  });
}

export function create(o) {
  const C = fit(o.palette, 8);
  const R = new Rng(o.seed);
  const ps = new Particles({ rng: R, ground: () => 999, sparks: [WHITE, C[6], C[5], C[3]] });
  const lights = new Lights();

  // Normalized [0..1] micro-arc slots so they adapt automatically to any configured chain length
  const microArcs = Array.from({ length: 5 }, (_, i) => ({
    tStart: i * 0.2 + 0.04,
    life: 0.11,
    uPos: 0.16 + ((i * 0.37) % 0.68),
    du: R.sign() * R.range(2.5, 4.6),
    dv: R.sign() * R.range(6.0, 8.8),
    midShift: R.range(-1.5, 1.5),
  }));

  let t = 0;
  let lastPulseSlot = -1;

  return {
    update(dt) {
      t += dt;
      const wt = ((t % T_LOOP) + T_LOOP) % T_LOOP;
      const { len, ang } = resolveChainSpec(o);
      const ca = Math.cos(ang);
      const sa = Math.sin(ang);

      // Brief light flash every 0.20 s at both monster shackles as pulses transfer between targets
      const slot = Math.floor(wt / 0.2);
      if (slot !== lastPulseSlot) {
        lastPulseSlot = slot;
        lights.add(4 * ca, 4 * sa, 18, WHITE, 0.72, 0.14);
        lights.add((len - 4) * ca, (len - 4) * sa, 16, C[5], 0.55, 0.14);
      }

      ps.update(dt);
      lights.update(dt);
    },

    draw(p) {
      const wt = ((t % T_LOOP) + T_LOOP) % T_LOOP;
      const uT = wt / T_LOOP;
      const phase = uT * TAU;

      const { len, ang } = resolveChainSpec(o);
      const ca = Math.cos(ang);
      const sa = Math.sin(ang);
      const toWX = (lx, ly) => lx * ca - ly * sa;
      const toWY = (lx, ly) => lx * sa + ly * ca;

      const pulseA = pulseBoost(4, len, uT);
      const pulseB = pulseBoost(len - 4, len, uT);

      // 1. Segmented core glowLine along the swaying chain spine
      const segs = Math.max(6, Math.round(len / 18));
      const xStart = 10;
      const xEnd = len - 10;
      for (let s = 0; s < segs; s++) {
        const lx0 = xStart + (s / segs) * (xEnd - xStart);
        const lx1 = xStart + ((s + 1) / segs) * (xEnd - xStart);
        const lxm = (lx0 + lx1) * 0.5;
        const boost = pulseBoost(lxm, len, uT);
        const ly0 = spineY(lx0, len, phase);
        const ly1 = spineY(lx1, len, phase);
        p.glowLine(
          toWX(lx0, ly0),
          toWY(lx0, ly0),
          toWX(lx1, ly1),
          toWY(lx1, ly1),
          4.2,
          boost > 0.35 ? C[6] : C[4],
          0.26 + 0.24 * boost
        );
      }

      // 2. Soul-shackle anchor rings at both Monster A (lx = 4) and Monster B (lx = len - 4)
      drawAnchorShackle(p, 4, 1, pulseA, C, phase, ca, sa);
      drawAnchorShackle(p, len - 4, -1, pulseB, C, phase + Math.PI, ca, sa);

      // 3. Adaptive interlocking 3D chain links spanning [14, len - 14]
      const span = Math.max(12, len - 28);
      const linkCount = Math.max(2, Math.round(span / TARGET_PITCH));
      const pitch = span / linkCount;

      shadeLocal(p, 8, -18, len - 8, 18, ca, sa, (lx, ly, out) => {
        const approxIdx = Math.floor((lx - 14) / pitch);
        let best = null;
        for (let k = approxIdx - 1; k <= approxIdx + 1; k++) {
          const hit = sampleLink(lx, ly, k, linkCount, pitch, len, phase, uT, C);
          if (hit && (!best || hit.z > best.z)) {
            best = hit;
          }
        }
        if (best) {
          out.c = best.c;
          out.a = best.a;
        }
      });

      // 4. Flowing energy orb glow halos moving from Monster B -> Monster A
      const pulses = Math.max(2, Math.round(len / 45));
      const travel = Math.max(16, len - 8);
      for (let i = 0; i < pulses; i++) {
        const f = (uT * pulses + i) % 1;
        const plx = len - 4 - f * travel;
        const ply = spineY(plx, len, phase);
        const env = Math.sin(f * Math.PI);
        const wx = toWX(plx, ply);
        const wy = toWY(plx, ply);
        p.glow(wx, wy, 10, C[6], 0.45 * env);
        p.glow(wx, wy, 4.5, WHITE, 0.52 * env);
      }

      // 5. Short transverse micro-arcs along the chain
      for (const arc of microArcs) {
        const localT = ((wt - arc.tStart) + T_LOOP) % T_LOOP;
        if (localT < arc.life) {
          const au = localT / arc.life;
          const ak = Math.sin(au * Math.PI);
          const lxBase = 16 + arc.uPos * (len - 32);
          const lyBase = spineY(lxBase, len, phase);

          const p0x = toWX(lxBase, lyBase);
          const p0y = toWY(lxBase, lyBase);
          const p1x = toWX(lxBase + arc.du * 0.5 + arc.midShift, lyBase + arc.dv * 0.55);
          const p1y = toWY(lxBase + arc.du * 0.5 + arc.midShift, lyBase + arc.dv * 0.55);
          const p2x = toWX(lxBase + arc.du, lyBase + arc.dv);
          const p2y = toWY(lxBase + arc.du, lyBase + arc.dv);

          p.line(p0x, p0y, p1x, p1y, WHITE, ak);
          p.line(p1x, p1y, p2x, p2y, C[6], ak * 0.9);
          p.glowLine(p0x, p0y, p2x, p2y, 3, C[5], 0.42 * ak);
        }
      }

      // 6. Seamless cyclic soul motes drifting from Monster B toward Monster A
      cycle(
        wt,
        T_LOOP,
        10,
        o.seed,
        (g) => g.range(0.45, 0.85),
        (age, life, Rl) => {
          const u = age / life;
          const env = Math.sin(u * Math.PI);
          const lxStart = len * Rl.range(0.7, 0.96);
          const lxEnd = len * Rl.range(0.04, 0.3);
          const mlx = lxStart + (lxEnd - lxStart) * u;
          const orbitR = Rl.range(3.5, 9.0) * (1 - u * 0.35);
          const mly = spineY(mlx, len, phase) + Math.sin(u * TAU * 2 + Rl.range(0, TAU)) * orbitR;
          const wx = toWX(mlx, mly);
          const wy = toWY(mlx, mly);

          p.dot(wx, wy, env > 0.7 ? WHITE : C[6], 0.9 * env);
          p.glow(wx, wy, 4.5, C[5], 0.25 * env);
        }
      );

      ps.draw(p);
      lights.draw(p);
    },
  };
}
