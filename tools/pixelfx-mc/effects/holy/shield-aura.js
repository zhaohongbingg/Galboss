import { fit, sparkle, cycle, clamp, TAU, WHITE } from "../../core/index.js";

export const meta = {
  name: "Shield aura",
  desc: "A golden octagonal energy barrier enclosing a standing figure, with double-pixel bevelled edges, travelling edge highlights, a tilted ground ring and rising sparks of light.",
  tags: ["holy", "shield", "aura", "barrier", "defense", "buff", "gold", "loop"],
  size: [176, 192],
  origin: [88, 184],
  duration: 2.4,
  loop: true,
  fps: 20,
  bg: "ground",
  options: {
    palette: ["#3b1d2a", "#7a3a2c", "#c4652e", "#f0a040", "#ffd25e", "#fff0a0", "#fffbe0", "#ffffff"],
    seed: 3,
  },
};

const CY = -74;                                  // vertical centre of the octagonal dome (feet at y = 0)
const APOTHEM = 60;                              // 120 x 120 px regular octagon enclosing a standing figure
const CHAMFER = APOTHEM * (Math.SQRT2 - 1);      // ~24.85 px half-length of each flat edge

// 8 vertices of the regular octagon in clockwise order starting at top-left chamfer end
const VERTS = [
  [-CHAMFER, CY - APOTHEM],
  [CHAMFER, CY - APOTHEM],
  [APOTHEM, CY - CHAMFER],
  [APOTHEM, CY + CHAMFER],
  [CHAMFER, CY + APOTHEM],
  [-CHAMFER, CY + APOTHEM],
  [-APOTHEM, CY + CHAMFER],
  [-APOTHEM, CY - CHAMFER],
];

// cumulative edge lengths for uniform perimeter parameterisation u in [0, 1)
const EDGES = VERTS.map((v0, i) => {
  const v1 = VERTS[(i + 1) % 8];
  return { x0: v0[0], y0: v0[1], x1: v1[0], y1: v1[1], len: Math.hypot(v1[0] - v0[0], v1[1] - v0[1]) };
});
const PERIM = EDGES.reduce((s, e) => s + e.len, 0);

function pointOnOctagon(u) {
  let d = (((u % 1) + 1) % 1) * PERIM;
  for (let i = 0; i < 8; i++) {
    const e = EDGES[i];
    if (d <= e.len || i === 7) {
      const f = clamp(d / e.len);
      return [e.x0 + (e.x1 - e.x0) * f, e.y0 + (e.y1 - e.y0) * f];
    }
    d -= e.len;
  }
  return [VERTS[0][0], VERTS[0][1]];
}

// 1 px tilted oval on the ground plane (sin(g) < 0 is the far half behind the caster)
function groundOval(p, cx, cy, rx, ry, cFn, aFn, keep) {
  const n = Math.ceil(Math.max(rx, ry) * TAU * 1.6) + 8;
  for (let i = 0; i < n; i++) {
    const g = (i / n) * TAU;
    if (keep && !keep(g)) continue;
    p.dot(cx + Math.cos(g) * rx, cy + Math.sin(g) * ry, cFn(g), aFn(g));
  }
}

export function draw(p, t, o) {
  const C = fit(o.palette, 8);
  const T = meta.duration;
  // wrap time modulo T so t = 0 and t = duration evaluate bit-for-bit identically
  const wt = ((t % T) + T) % T;
  const uT = wt / T;
  const phase = uT * TAU;
  const breathe = 0.5 + 0.5 * Math.sin(phase * 2);

  // 1. Ambient golden aura in the light channel
  p.glow(0, CY, 76, C[4], 0.22 + 0.05 * breathe);
  p.glow(0, -2, 42, C[4], 0.22 + 0.04 * breathe);

  // 2. Far half of the ground ring (behind the barrier)
  groundOval(p, 0, 0, 52, 8.5,
    (g) => (Math.cos(g - phase) > 0.7 ? C[4] : C[3]),
    () => 0.42,
    (g) => Math.sin(g) < 0);
  groundOval(p, 0, 0, 43, 7.0,
    () => C[2],
    () => 0.32,
    (g) => Math.sin(g) < 0);

  // 3. Octagonal energy shell: faint translucent interior + crisp 1 px double-layer bevelled rim
  const pad = 4;
  p.shade(-APOTHEM - pad, CY - APOTHEM - pad, APOTHEM + pad, CY + APOTHEM + pad, (x, y, out) => {
    const ax = Math.abs(x);
    const ay = Math.abs(y - CY);
    // signed distance to the regular octagon boundary (0 on perimeter, negative inside)
    const sd = Math.max(ax, ay, (ax + ay) * Math.SQRT1_2) - APOTHEM;
    if (sd > 0.5) return;

    // travelling perimeter wave intensity at angle around centre
    const ang = Math.atan2(y - CY, x);
    const wave = 0.5 + 0.5 * Math.sin(ang * 3 - phase * 2);
    const shimmer = 0.5 + 0.5 * Math.sin(ang * 5 + phase * 3);

    if (sd > -1.0) {
      // outer 1 px bright rim
      out.c = wave > 0.86 ? WHITE : wave > 0.55 ? C[6] : C[5];
      out.a = 0.92 + 0.08 * breathe;
    } else if (sd > -2.0) {
      // 1 px dark bevel groove separating the double outline
      out.c = C[1];
      out.a = 0.65;
    } else if (sd > -3.0) {
      // inner 1 px secondary rim
      out.c = shimmer > 0.78 ? C[6] : C[4];
      out.a = 0.78;
    } else {
      // very subtle hollow interior fill with gentle rim falloff and upward energy bands
      const rimFalloff = clamp(1 - (-sd - 3) / 18);
      const scan = 0.5 + 0.5 * Math.sin(y * 0.16 + phase * 2 + Math.sin(x * 0.09) * 1.4);
      const a = 0.03 + 0.055 * rimFalloff + 0.03 * scan * rimFalloff;
      if (a <= 0.025) return;
      out.c = scan > 0.72 && rimFalloff > 0.4 ? C[5] : C[4];
      out.a = a;
    }
  });

  // Base edge glow on all 8 segments of the octagon
  for (let i = 0; i < 8; i++) {
    const e = EDGES[i];
    const pulse = 0.5 + 0.5 * Math.sin((i / 8) * TAU - phase * 2);
    p.glowLine(e.x0, e.y0, e.x1, e.y1, 4, C[5], 0.12 + 0.08 * pulse);
  }

  // Subtle corner node glints on the 8 octagon vertices
  for (let i = 0; i < 8; i++) {
    const [vx, vy] = VERTS[i];
    const k = 0.5 + 0.5 * Math.sin((i / 8) * TAU * 2 - phase * 2);
    if (k > 0.65) {
      p.px(vx, vy, k > 0.9 ? WHITE : C[6]);
      p.glow(vx, vy, 5, C[6], 0.22 * k);
    }
  }

  // 4. Three travelling highlights racing along the octagonal edges
  const streaks = 3;
  for (let s = 0; s < streaks; s++) {
    const headU = (uT + s / streaks) % 1;
    const tailSteps = 18;
    const tailSpan = 0.11;
    for (let k = tailSteps; k >= 0; k--) {
      const f = k / tailSteps;                   // 0 = bright head, 1 = faded tail
      const [px, py] = pointOnOctagon(headU - f * tailSpan);
      const c = f < 0.18 ? WHITE : f < 0.52 ? C[6] : C[5];
      p.dot(px, py, c, (1 - f) * 0.95);
    }
    const [hx, hy] = pointOnOctagon(headU);
    p.px(hx, hy, WHITE);
    p.glow(hx, hy, 8, C[6], 0.48);
    sparkle(p, hx, hy, 1.8 + 0.8 * breathe, WHITE, C[6], 0.45);
  }

  // 5. Near half of the ground ring (in front of the barrier, brighter + orbiting glints)
  groundOval(p, 0, 0, 52, 8.5,
    (g) => {
      const glint = Math.max(Math.cos(g - phase), Math.cos(g - phase - Math.PI));
      return glint > 0.92 ? WHITE : glint > 0.65 ? C[6] : C[5];
    },
    () => 0.92,
    (g) => Math.sin(g) >= 0);
  groundOval(p, 0, 0, 43, 7.0,
    () => C[4],
    () => 0.62,
    (g) => Math.sin(g) >= 0);
  // Two bright orbiting nodes along the near ground arc
  for (let k = 0; k < 2; k++) {
    const g = phase + k * Math.PI;
    if (Math.sin(g) >= -0.1) {
      const gx = Math.cos(g) * 52;
      const gy = Math.sin(g) * 8.5;
      p.px(gx, gy, WHITE);
      p.glow(gx, gy, 7, C[6], 0.42);
    }
  }

  // 6. Eight rising light motes drifting up across the shield surface (seamless cycle)
  cycle(wt, T, 8, o.seed, (R) => R.range(0.7, 1.15), (age, life, R) => {
    const u = age / life;
    const env = Math.sin(u * Math.PI);
    const baseX = R.range(-48, 48);
    const startY = R.range(-16, -42);
    const rise = R.range(48, 84);
    const swayAmp = R.range(2, 5);
    const swayFreq = R.range(2, 4);
    const x = baseX + Math.sin(u * TAU * swayFreq + R.range(0, TAU)) * swayAmp;
    const y = startY - u * rise;

    const bright = env > 0.75 ? WHITE : C[6];
    p.dot(x, y, bright, env * 0.95);
    if (env > 0.45) {
      p.dot(x, y + 1, C[5], env * 0.55);
    }
    if (R.chance(0.45) && env > 0.6) {
      sparkle(p, x, y, 1.4 * env, WHITE, C[5], 0.35 * env);
    }
    p.glow(x, y, 5, C[5], 0.28 * env);
  });
}
