import { Particles, Bits, Lights, P, B, Rng, fit, clamp, smooth, ease, TAU, WHITE } from "../../core/index.js";

export const meta = {
  name: "Hammer smash",
  desc: "A heavy square-headed warhammer winds up overhead, swings down with a pixel-art speed smear, slams flat onto the ground with squash and white-hot flash, crackles with energy, and dissolves into rising embers.",
  tags: ["impact", "hammer", "smash", "heavy", "thunder", "ground", "one-shot"],
  size: [224, 256],
  origin: [112, 244],
  duration: 1.3,
  loop: false,
  fps: 20,
  bg: "ground",
  options: {
    palette: ["#101526", "#1e2d4d", "#2f5280", "#4982b8", "#75b8e8", "#b3e2ff", "#e6f7ff", "#ffffff"],
    seed: 7,
  },
};

const WINDUP_T = 0.10;
const IMPACT_T = 0.25;

// Deterministic 2D hash for crisp pixel-erosion dissolution
function pxHash(x, y) {
  const n = Math.sin(x * 127.1 + y * 311.7) * 43758.5453123;
  return n - Math.floor(n);
}

// Compute hammer world pose (cx, cy, angle in rad) during wind-up and downward swing
function hammerSwingPose(t) {
  if (t < WINDUP_T) {
    // Cock back slightly upward and tilt to -21 deg while charging
    const u = ease.outQuad(clamp(t / WINDUP_T));
    return {
      cx: -6 - 5 * u,
      cy: -146 - 10 * u,
      angle: -0.24 - 0.13 * u,
    };
  }
  const u = clamp((t - WINDUP_T) / (IMPACT_T - WINDUP_T));
  const k = ease.inCubic(u);
  return {
    cx: -11 * (1 - k),
    cy: -156 * (1 - k),
    angle: -0.37 * (1 - k),
  };
}

// 8-point crisp pixel lens flare on impact
function flare(p, x, y, L, k, C) {
  if (L < 2 || k <= 0.02) return;
  for (const [dx, dy, len] of [
    [1, 0, L],
    [0, 1, L * 0.42],
    [0.707, 0.707, L * 0.22],
    [0.707, -0.707, L * 0.22],
  ]) {
    for (const s of [-1, 1]) {
      for (let d = 1; d <= len; d++) {
        const u = d / len;
        const a = (u < 0.65 ? 1 : (1 - u) / 0.35) * k;
        p.dot(x + s * dx * d, y + s * dy * d, u < 0.32 ? WHITE : u < 0.68 ? C[6] : C[5], a);
        if (dy === 0 && u < 0.38) {
          for (const e of [-1, 1]) p.dot(x + s * d, y + e, u < 0.18 ? C[6] : C[5], a * 0.85);
        }
      }
    }
  }
  p.glowLine(x - L, y, x + L, y, 6, C[6], 0.55 * k);
  p.glowLine(x, y - L * 0.42, x, y + L * 0.42, 4, C[5], 0.35 * k);
}

// Tilted ground ring (sy ~ 0.17) with 1 px bright rim and dark inner bevel
function groundRing(p, cx, cy, rx, ry, hot, a, C) {
  if (rx < 1 || a <= 0.02) return;
  const n = Math.ceil(rx * TAU * 1.6) + 12;
  for (let i = 0; i < n; i++) {
    const g = (i / n) * TAU;
    const near = Math.sin(g) >= 0;
    const px = cx + Math.cos(g) * rx;
    const py = cy + Math.sin(g) * ry;
    const cRim = hot > 0.5 ? WHITE : near ? C[6] : C[4];
    const cInner = near ? C[3] : C[1];
    p.dot(px, py, cRim, a * (near ? 1 : 0.72));
    p.dot(px, py - 1, cInner, a * 0.65);
  }
}

// Pixel-art motion smear wedge bridging previous frame to current hammer head during fast drop
function drawMotionSmear(p, x0, y0, x1, y1, C, k) {
  if (k <= 0.02 || y1 - y0 < 6) return;
  const minX = Math.min(x0, x1) - 31;
  const maxX = Math.max(x0, x1) + 31;
  const minY = y0 - 24;
  const maxY = y1 - 4;
  const spanY = Math.max(1, maxY - minY);

  p.shade(minX, minY, maxX, maxY, (x, y, out) => {
    const u = clamp((y - minY) / spanY); // 0 = top tail, 1 = right behind hammer head
    const centerX = x0 + (x1 - x0) * u;
    const halfW = 14 + 16 * Math.pow(u, 0.7);
    const dx = Math.abs(x - centerX);
    if (dx > halfW) return;

    // Vertical speed-line striations
    const stripe = 0.65 + 0.35 * Math.sin(x * 0.95 + (x > centerX ? 0.8 : -0.8));
    const edgeFade = 1 - Math.pow(dx / halfW, 2.2);
    const a = Math.pow(u, 1.35) * edgeFade * stripe * 0.72 * k;
    if (a <= 0.04) return;

    out.c = u > 0.82 && dx < halfW * 0.75 ? C[6] : u > 0.5 ? C[5] : C[4];
    out.a = a;
  });

  p.glowLine(x0, y0 - 10, x1, y1 - 10, 14, C[5], 0.28 * k);
}

// Rotatable, squashable square-headed warhammer (anchor = bottom striking-face center; no hand/body)
function drawHammer(p, cx, cy, angle, sx, sy, C, alpha, whiteFlash, dissolve = 0, tintStep = 0) {
  if (alpha <= 0.02 || dissolve >= 1) return;
  const col = (idx) => C[clamp(idx + tintStep, 0, 7)];
  const ca = Math.cos(angle);
  const sa = Math.sin(angle);

  // Handle axis in local hammer coordinates: starts at (0, -37) and goes 52 px up-right at 28 deg
  const hDirX = 0.4695;
  const hDirY = -0.8829;
  const hLen = 52;

  // Conservative world-space bounding box covering both 64x40 head and 52px angled handle
  const boundR = 96 * Math.max(sx, sy);
  p.shade(cx - boundR, cy - boundR, cx + boundR, cy + 28 * sy, (wx, wy, out) => {
    const dx = wx - cx;
    const dy = wy - cy;
    // Inverse rotation + squash/stretch into local hammer space
    const lx = (dx * ca + dy * sa) / sx;
    const ly = (-dx * sa + dy * ca) / sy;

    // 1. Test heavy warhammer head: 64 x 40 px (lx: -32..32, ly: -40..0)
    const ax = Math.abs(lx);
    const ay = Math.abs(ly + 20);
    const inCap = ax <= 32 && ay <= 20 && ax + ay <= 49; // 3 px outer corner chamfers
    const inCoreBlock = ax <= 22 && ay <= 18.5;
    const inHead = inCap || inCoreBlock;

    // 2. Test handle + collar + pommel
    let inHandle = false;
    let hAlong = 0;
    let hPerp = 0;
    let hHalfW = 0;
    if (!inHead) {
      const hx = lx;
      const hy = ly + 36;
      hAlong = hx * hDirX + hy * hDirY;
      if (hAlong >= 0 && hAlong <= hLen) {
        hPerp = -hx * hDirY + hy * hDirX; // negative = lit top-left side, positive = shaded bottom-right
        hHalfW = hAlong < 5 ? 4.8 : hAlong > hLen - 6 ? 4.4 : 2.7;
        if (Math.abs(hPerp) <= hHalfW) {
          inHandle = true;
        }
      }
    }

    if (!inHead && !inHandle) return;

    // Pixel-erosion dissolution from outer edges & handle inward
    if (dissolve > 0) {
      const edgeBias = inHandle ? 0.35 : clamp((ax / 32) * 0.45 + (ay / 20) * 0.35);
      const threshold = dissolve * 1.35 - (0.35 - edgeBias * 0.35);
      if (pxHash(Math.round(lx), Math.round(ly)) < threshold) return;
    }

    if (whiteFlash > 0.5) {
      const rim = inHead
        ? ax >= 30.8 || ay >= 18.8 || ax + ay >= 47.8
        : Math.abs(hPerp) >= hHalfW - 1 || hAlong >= hLen - 1;
      out.c = rim ? C[6] : WHITE;
      out.a = alpha;
      return;
    }

    if (inHead) {
      const outer = ax >= 31.0 || ay >= 19.0 || ax + ay >= 48.0 || (!inCap && ay >= 17.5);
      const bevel = ax >= 29.4 || ay >= 17.4 || ax + ay >= 46.4;
      const inBand = ax <= 5.2;
      const inRune = ax <= 2.2 && ly >= -30 && ly <= -10;

      if (outer) {
        out.c = col(0);
      } else if (inRune) {
        out.c = ax <= 1.0 ? WHITE : col(6);
      } else if (inBand) {
        // Central iron/energy collar framing the glowing rune
        if (ax >= 4.2) out.c = col(0);
        else if (ly <= -36.5) out.c = col(5);
        else if (ly >= -3.5) out.c = col(1);
        else out.c = col(2);
      } else if (bevel) {
        // Crisp 1.5 px top-left specular bevel & bottom striking face rim
        if (ly <= -37.4 || lx <= -29.4) out.c = col(6);
        else if (ly >= -2.4) out.c = col(5);
        else out.c = col(2);
      } else if (ax >= 22.0) {
        // Heavy end striking blocks with subtle horizontal panel grooves
        const groove = Math.abs(ly + 28) < 0.6 || Math.abs(ly + 12) < 0.6;
        if (groove) out.c = col(1);
        else if (ly < -22) out.c = col(4);
        else if (ly > -9) out.c = col(2);
        else out.c = col(3);
      } else {
        // Recessed mid-head plates
        out.c = ly < -23 ? col(3) : ly > -9 ? col(1) : col(2);
      }
      out.a = alpha;
      return;
    }

    // Handle & pommel shading
    const hOuter = Math.abs(hPerp) >= hHalfW - 0.9 || hAlong >= hLen - 0.9;
    if (hOuter) {
      out.c = col(0);
    } else if (hAlong < 5 || hAlong > hLen - 6) {
      // Metallic collar & pommel cap
      out.c = hPerp < -0.6 ? col(5) : hPerp > 1.2 ? col(1) : col(3);
    } else {
      // Grip wrap bands along the shaft
      const gripBand = Math.floor(hAlong / 5) % 2 === 0;
      if (hPerp < -0.9) out.c = gripBand ? col(5) : col(4);
      else if (hPerp > 0.9) out.c = col(1);
      else out.c = gripBand ? col(3) : col(2);
    }
    out.a = alpha;
  });

  // Glowing core rune along the hammer head axis
  if (tintStep === 0 && dissolve < 0.7) {
    const k = (1 - dissolve) * alpha;
    const rx0 = cx + 10 * sa * sy;
    const ry0 = cy - 10 * ca * sy;
    const rx1 = cx + 30 * sa * sy;
    const ry1 = cy - 30 * ca * sy;
    p.glowLine(rx0, ry0, rx1, ry1, 5, C[5], 0.5 * k);
  }
}

export function create(o) {
  const C = fit(o.palette, 8);
  const R = new Rng(o.seed);
  const floor = () => 0;
  const ps = new Particles({ rng: R, ground: floor, sparks: [WHITE, C[6], C[5], C[3]] });
  const bits = new Bits({ ground: floor });
  const lights = new Lights();

  // Stage 1 (0 ~ 0.25s): Overhead converging energy streaks pulling into the cocked hammer
  for (let i = 0; i < 16; i++) {
    const delay = R.range(0, 0.14);
    const life = R.range(0.08, 0.12);
    const targetY = -152 + (delay / 0.25) * 90;
    const side = i % 2 === 0 ? -1 : 1;
    const startX = side * R.range(42, 84);
    const startY = targetY + R.range(-48, 28);
    const vx = (R.range(-10, 10) - startX) / life;
    const vy = (targetY - startY) / life;
    ps.spawn(P.STREAK, startX, startY, vx, vy, life, i % 3 === 0 ? WHITE : C[5], 1.1, delay);
  }

  // Pre-build 6 radial ground crack paths on the ground plane
  const cracks = Array.from({ length: 6 }, (_, i) => {
    const baseAng = (i / 6) * TAU + R.range(-0.22, 0.22);
    const len = R.range(36, 72);
    const segs = 5;
    const pts = [[0, 0]];
    for (let s = 1; s <= segs; s++) {
      const f = s / segs;
      const jit = R.range(-0.18, 0.18) * (1 - f * 0.4);
      const r = len * f;
      pts.push([Math.cos(baseAng + jit) * r, Math.sin(baseAng + jit) * r * 0.22]);
    }
    return { pts, near: Math.sin(baseAng) >= 0 };
  });

  // Pre-build 4 surface lightning arcs that crackle across the planted hammer head (0.30 ~ 0.56s)
  const headArcs = Array.from({ length: 4 }, (_, i) => {
    const start = i * 0.055;
    const pts = [];
    const xStart = R.range(-28, -14);
    const xEnd = R.range(14, 28);
    const steps = 4;
    for (let s = 0; s <= steps; s++) {
      const f = s / steps;
      pts.push([
        xStart + (xEnd - xStart) * f,
        -20 + R.range(-14, 14) * Math.sin(f * Math.PI) + R.range(-3, 3),
      ]);
    }
    return { start, life: 0.09, pts };
  });

  let t = 0;
  let impacted = false;

  function triggerImpact() {
    impacted = true;

    // Stage 2: Strong impact light burst (decays cleanly before t = 1.0s)
    lights.add(0, -16, 132, C[5], 0.95, 0.58);
    lights.add(0, -8, 56, WHITE, 0.95, 0.16);
    lights.add(0, 0, 92, C[4], 0.55, 0.72);

    // Stage 3: Tumbling stone/energy shards that bounce on y = 0 and settle
    for (let i = 0; i < 14; i++) {
      const side = i % 2 === 0 ? -1 : 1;
      const ang = -Math.PI * 0.5 + side * R.range(0.35, 1.28);
      const spd = R.range(95, 245);
      bits.add({
        kind: B.SHARD,
        x: side * R.range(6, 28),
        y: -4,
        vx: Math.cos(ang) * spd,
        vy: Math.sin(ang) * spd - 35,
        g: 0.85,
        drag: 0.65,
        bounce: 0.36,
        life: R.range(0.55, 0.88),
        a: R.range(0, TAU),
        va: R.range(-22, 22),
        s: R.range(2.2, 4.4),
        c: R.pick([C[6], C[5], C[4], C[3]]),
      });
    }

    // Stage 3: High-velocity sparks spraying outward & bouncing off ground
    for (let i = 0; i < 34; i++) {
      const side = i % 2 === 0 ? -1 : 1;
      const ang = -Math.PI * 0.5 + side * R.range(0.45, 1.42);
      const spd = R.range(135, 345);
      ps.spawn(
        P.SPARK,
        side * R.range(8, 30),
        -3,
        Math.cos(ang) * spd,
        Math.sin(ang) * spd - 20,
        R.range(0.22, 0.55),
        WHITE,
        1,
        R.range(0, 0.03)
      );
    }

    // Stage 3 -> 4: Radial ground dust billowing left & right, then rising as fading smoke
    for (let i = 0; i < 18; i++) {
      const side = i % 2 === 0 ? -1 : 1;
      const spd = R.range(45, 145);
      ps.spawn(
        P.DUST,
        side * R.range(14, 34),
        -2,
        side * spd,
        R.range(-28, -6),
        R.range(0.38, 0.68),
        R.pick([C[4], C[3], C[2]]),
        R.range(3.5, 6.5),
        R.range(0, 0.04)
      );
    }

    for (let i = 0; i < 12; i++) {
      const side = i % 2 === 0 ? -1 : 1;
      ps.spawn(
        P.SMOKE,
        side * R.range(16, 56),
        -4,
        side * R.range(12, 38),
        R.range(-36, -16),
        R.range(0.55, 0.85),
        R.pick([C[3], C[2], C[1]]),
        R.range(5, 9),
        R.range(0.08, 0.22)
      );
    }

    // Stage 4: Rising embers from both the ground and the dissolving hammer body
    for (let i = 0; i < 20; i++) {
      const fromHammer = i < 8;
      ps.spawn(
        P.EMBER,
        fromHammer ? R.range(-28, 28) : R.range(-50, 50),
        fromHammer ? R.range(-38, -8) : R.range(-10, -2),
        R.range(-22, 22),
        R.range(-72, -30),
        R.range(0.42, 0.72),
        R.pick([WHITE, C[6], C[5]]),
        R.range(0.8, 1.4),
        fromHammer ? R.range(0.24, 0.42) : R.range(0.06, 0.24)
      );
    }
  }

  return {
    update(dt) {
      t += dt;
      if (!impacted && t >= IMPACT_T) {
        triggerImpact();
      }
      ps.update(dt);
      bits.update(dt);
      lights.update(dt);
    },

    draw(p) {
      if (t >= meta.duration - 0.01) return;

      // Stage 1 (0 ~ 0.25s): Contracting ground target telegraph ring
      if (t < IMPACT_T) {
        const u = clamp(t / IMPACT_T);
        const rx = 60 * (1 - ease.inQuad(u)) + 14;
        groundRing(p, 0, 0, rx, rx * 0.17, 0, 0.35 + 0.55 * u, C);
        p.glow(0, 0, 28 + 22 * u, C[4], 0.24 * u);
      }

      // Stage 3 & 4 (0.25 ~ 0.90s): Ground cracks snapping open and cooling
      if (t >= IMPACT_T && t < 0.9) {
        const cu = (t - IMPACT_T) / 0.65;
        const crackAlpha = cu < 0.12 ? cu / 0.12 : clamp(1 - (cu - 0.25) / 0.75);
        const hot = cu < 0.22;
        for (const cr of cracks) {
          for (let s = 0; s < cr.pts.length - 1; s++) {
            const [x0, y0] = cr.pts[s];
            const [x1, y1] = cr.pts[s + 1];
            const frac = 1 - s / cr.pts.length;
            const c = hot && frac > 0.45 ? C[6] : cu < 0.45 && frac > 0.6 ? C[4] : C[1];
            p.line(x0, y0, x1, y1, c, crackAlpha * (cr.near ? 0.95 : 0.68));
            if (hot && s < 2) {
              p.glowLine(x0, y0, x1, y1, 3, C[5], 0.35 * crackAlpha * (1 - cu * 2));
            }
          }
        }
      }

      // Stage 3 (0.25 ~ 0.65s): Primary & secondary expanding ground shockwave rings
      if (t >= IMPACT_T && t < 0.65) {
        const su = (t - IMPACT_T) / 0.4;
        const r1 = 18 + 86 * ease.outCubic(su);
        const a1 = su < 0.15 ? 1 : clamp(1 - (su - 0.15) / 0.85);
        groundRing(p, 0, 0, r1, r1 * 0.17, su < 0.18 ? 1 : 0, a1, C);
        p.glowRing(0, 0, r1 * 0.85, 5, C[5], 0.35 * a1 * (1 - su));

        if (su > 0.1) {
          const su2 = (su - 0.1) / 0.9;
          const r2 = 12 + 56 * ease.outCubic(su2);
          groundRing(p, 0, 0, r2, r2 * 0.17, 0, (1 - su2) * 0.78, C);
        }
      }

      bits.draw(p);
      ps.draw(p);

      // Hammer body: wind-up (0~0.10s) -> smear slam (0.10~0.25s) -> hit-stop squash -> dissolve (0.50~0.72s)
      if (t < 0.72) {
        if (t < IMPACT_T) {
          const pose = hammerSwingPose(t);
          const appear = clamp(t / 0.06);

          // Speed smear + 2 trailing ghosts during the rapid downward swing (t >= WINDUP_T)
          if (t > WINDUP_T) {
            const swingU = clamp((t - WINDUP_T) / (IMPACT_T - WINDUP_T));
            const prevPose = hammerSwingPose(Math.max(WINDUP_T, t - 0.055));
            drawMotionSmear(p, prevPose.cx, prevPose.cy, pose.cx, pose.cy, C, swingU);

            for (let g = 2; g >= 1; g--) {
              const gt = t - g * 0.028;
              if (gt > WINDUP_T * 0.5) {
                const gp = hammerSwingPose(gt);
                drawHammer(p, gp.cx, gp.cy, gp.angle, 1, 1, C, (0.34 - g * 0.1) * swingU, 0, 0, 1);
              }
            }
          }

          drawHammer(p, pose.cx, pose.cy, pose.angle, 1, 1, C, 0.35 + 0.65 * appear, 0, 0, 0);
          p.glow(pose.cx, pose.cy - 20, 38, C[5], 0.28 + 0.26 * clamp(t / IMPACT_T));
        } else {
          const post = t - IMPACT_T;
          // 1~2 white-hot hit-stop frames (0.25 ~ 0.32s)
          const whiteFlash = post < 0.075 ? 1 : 0;
          // Heavy impact squash on first frame, then planted settle on y = 0
          const squash = post < 0.06 ? 1 - post / 0.06 : 0;
          const sx = 1 + 0.065 * squash;
          const sy = 1 - 0.065 * squash;
          const settleY = post < 0.06 ? 1.5 * squash : 0;
          const recoilAng = post < 0.06 ? 0.025 * squash : 0;
          // Pixel-erosion dissolution from 0.25s to 0.47s post-impact (t = 0.50 ~ 0.72s)
          const dissolve = post < 0.25 ? 0 : clamp((post - 0.25) / 0.22);

          drawHammer(p, 0, settleY, recoilAng, sx, sy, C, 1, whiteFlash, dissolve, 0);

          // Crackling surface lightning arcs across the planted hammer head
          for (const arc of headArcs) {
            const au = (post - arc.start) / arc.life;
            if (au > 0 && au < 1 && dissolve < 0.45) {
              const ak = Math.sin(au * Math.PI);
              for (let s = 0; s < arc.pts.length - 1; s++) {
                const [x0, y0] = arc.pts[s];
                const [x1, y1] = arc.pts[s + 1];
                p.line(x0, y0, x1, y1, s === 1 ? WHITE : C[6], ak);
                p.glowLine(x0, y0, x1, y1, 3, C[5], 0.45 * ak);
              }
            }
          }
        }
      }

      // Stage 2 (0.25 ~ 0.45s): Impact white-hot flash & lens flare
      if (t >= IMPACT_T && t < 0.45) {
        const fu = (t - IMPACT_T) / 0.2;
        const fk = Math.pow(1 - fu, 1.4);
        flare(p, 0, -4, 78 * (1 - fu * 0.45), fk, C);
        p.glow(0, -10, 72, C[5], 0.58 * fk);
        if (fu < 0.45) {
          p.glow(0, -4, 26, WHITE, 0.88 * (1 - fu / 0.45));
        }
      }

      lights.draw(p);
    },
  };
}
