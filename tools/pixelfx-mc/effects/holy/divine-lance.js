import { Particles, Lights, P, Rng, fit, clamp, ease, TAU, WHITE } from "../../core/index.js";

export const meta = {
  name: "Divine lance",
  desc: "Gungnir condenses at the caster's stance, thrusts forward along the strike line in a crisp golden flash with 3D orbital rings, and dissolves from tail to tip into rising embers.",
  tags: ["holy", "lance", "spear", "gungnir", "radiant", "gold", "directional", "one-shot"],
  size: [240, 96],
  origin: [216, 48],
  duration: 1.0,
  loop: false,
  fps: 20,
  bg: "dark",
  options: {
    palette: ["#3b1d2a", "#7a3a2c", "#c4652e", "#f0a040", "#ffd25e", "#fff0a0", "#fffbe0", "#ffffff"],
    seed: 7,
  },
};

const FORM_T = 0.22;
const THRUST_END_T = 0.35;
const DISINTEGRATE_T = 0.68;

// Lower the spear centerline by 8 px so it aligns with the caster's chest/hand instead of floating above the head
const AXIS_Y = 8;

// Deterministic 2D spatial hash for crisp tail-to-tip pixel dissolution
function pxHash(x, y) {
  const n = Math.sin(x * 127.1 + y * 311.7) * 43758.5453123;
  return n - Math.floor(n);
}

// 8-point asymmetric pixel lens flare at the spear tip (bounded inside the 24 px right margin)
function tipFlare(p, x, y, L, k, C) {
  if (L < 2 || k <= 0.02) return;
  const dirs = [
    [1, 0, Math.min(18, L * 0.85)],
    [-1, 0, L * 1.65],
    [0, 1, L * 1.05],
    [0, -1, L * 1.05],
    [0.707, 0.707, L * 0.48],
    [0.707, -0.707, L * 0.48],
    [-0.707, 0.707, L * 0.58],
    [-0.707, -0.707, L * 0.58],
  ];
  for (const [dx, dy, len] of dirs) {
    for (let d = 1; d <= len; d++) {
      const u = d / len;
      const a = (u < 0.6 ? 1 : (1 - u) / 0.4) * k;
      p.dot(x + dx * d, y + dy * d, u < 0.35 ? WHITE : u < 0.7 ? C[6] : C[5], a);
      if (dy === 0 && u < 0.4) {
        for (const e of [-1, 1]) {
          p.dot(x + dx * d, y + e, u < 0.2 ? C[6] : C[5], a * 0.85);
        }
      }
    }
  }
  p.glowLine(x - L * 1.4, y, x + Math.min(16, L * 0.8), y, 4, C[6], 0.42 * k);
  p.glowLine(x, y - L, x, y + L, 3, C[5], 0.32 * k);
}

// High-contrast 3D foreshortened orbital ring wrapping around the spear axis (z < 0 back, z >= 0 front)
function orbitalRing3D(p, cx, cy, rx, ry, rotPhase, C, alpha, pass) {
  if (ry < 1 || alpha <= 0.02) return;
  const n = Math.ceil(ry * TAU * 2.0) + 12;
  for (let i = 0; i < n; i++) {
    const g = (i / n) * TAU;
    const z = Math.cos(g);
    if ((pass === "back" && z >= 0) || (pass === "front" && z < 0)) continue;
    const px = cx + z * rx;
    const py = cy + Math.sin(g) * ry;
    const glint = 0.5 + 0.5 * Math.cos(g - rotPhase);
    if (pass === "front") {
      // Dark outer rim + bright golden-white inner core so the ring reads clearly against bright MC skies
      p.dot(px + 1, py, C[1], alpha * 0.75);
      p.dot(px, py, glint > 0.82 ? WHITE : glint > 0.45 ? C[6] : C[4], alpha);
    } else {
      p.dot(px, py, glint > 0.65 ? C[3] : C[1], alpha * 0.58);
    }
  }
}

// Draw the ~202 px 3D-faceted golden divine lance (tip at tipX, y = AXIS_Y; body extends left)
function drawLance(p, tipX, C, { alpha = 1, form = 1, whiteFlash = 0, erode = 0, tintStep = 0 } = {}) {
  if (alpha <= 0.02 || form <= 0.02 || erode >= 1) return;
  const col = (idx) => C[clamp(idx + tintStep, 0, 7)];

  const xMin = Math.max(-214, Math.floor(tipX - 204));
  const xMax = Math.min(22, Math.ceil(tipX + 2));
  const yPad = 15;

  p.shade(xMin, AXIS_Y - yPad, xMax, AXIS_Y + yPad, (wx, wy, out) => {
    const lx = wx - tipX; // 0 at razor tip, -202 at tail end
    const ly = wy - AXIS_Y;
    const ay = Math.abs(ly);
    if (lx > 0 || lx < -202) return;

    // 1. Half-width profile across the 4 anatomical sections
    let halfW = 0;
    let part = 0; // 1 = spearhead, 2 = crossguard, 3 = shaft, 4 = tail fin

    if (lx >= -54) {
      part = 1; // Elongated willow-leaf spearhead (0 .. -54, max halfW = 9.5 at lx = -40)
      if (lx >= -40) {
        halfW = 9.5 * Math.pow(-lx / 40, 0.76);
      } else {
        halfW = 9.5 - 5.1 * ((-lx - 40) / 14);
      }
    } else if (lx >= -66) {
      part = 2; // Winged crossguard collar (-54 .. -66, flares to halfW = 13.0 -> 26 px width)
      const u = (-lx - 54) / 12;
      halfW = 4.5 + 8.5 * Math.pow(Math.sin(u * Math.PI), 0.65);
    } else if (lx >= -182) {
      part = 3; // Tapered fluted shaft (-66 .. -182, halfW tapers 4.5 -> 2.1)
      const u = (-lx - 66) / 116;
      halfW = 4.5 - 2.4 * u;
    } else {
      part = 4; // Flared tail pommel & sharp wake tip (-182 .. -202)
      const u = (-lx - 182) / 20;
      if (u < 0.25) {
        halfW = 2.1 + 2.3 * (u / 0.25);
      } else {
        halfW = 4.4 * (1 - (u - 0.25) / 0.75);
      }
    }

    const effHalfW = halfW * (0.24 + 0.76 * form);
    if (ay > effHalfW) return;

    // Tail-to-tip pixel erosion dissolution during stages 3 & 4
    if (erode > 0) {
      const posU = clamp((-lx) / 202); // 1 at tail, 0 at tip
      const edgeU = effHalfW > 0.5 ? ay / effHalfW : 1;
      const front = erode * 1.45 - (1 - posU) * 0.85 - (1 - edgeU) * 0.22;
      if (front > 0 && pxHash(Math.round(lx), Math.round(ly)) < front) return;
    }

    const edgeDist = effHalfW - ay;
    const isOuter = edgeDist <= 1.0 || (part === 1 && lx >= -1.2);
    const isBevel = edgeDist <= 2.4;
    const isCoreSpine = ay <= (part === 1 ? 0.95 : 0.7);

    if (whiteFlash > 0.5) {
      out.c = isOuter ? C[5] : WHITE;
      out.a = alpha;
      return;
    }

    // Crisp 1 px dark-bronze contour so the golden spear never washes out against bright MC sky
    if (isOuter && !(part === 1 && lx > -7)) {
      out.c = col(0);
      out.a = alpha;
      return;
    }

    if (part === 1) {
      // 3D 4-facet willow-leaf blade
      if (lx > -11 && ay <= effHalfW * 0.65) {
        out.c = WHITE;
      } else if (isCoreSpine) {
        out.c = lx > -34 ? WHITE : col(6);
      } else if (isBevel) {
        out.c = ly < 0 ? col(6) : col(4);
      } else {
        out.c = ly < 0 ? (lx > -26 ? col(6) : col(5)) : lx > -26 ? col(3) : col(2);
      }
      out.a = alpha;
      return;
    }

    if (part === 2) {
      // Winged crossguard: brighter tier + white-hot central gem
      const gemDist = Math.hypot((lx + 60) * 0.85, ly);
      if (gemDist <= 2.4) {
        out.c = gemDist <= 1.15 ? WHITE : col(6);
      } else if (isBevel) {
        out.c = ly < 0 ? WHITE : col(5);
      } else {
        out.c = ly < 0 ? col(6) : col(3);
      }
      out.a = alpha;
      return;
    }

    if (part === 3) {
      // Tapered 3D fluted shaft with central 1 px inner light core + 2 dark longitudinal grooves
      const ringBand = Math.abs(lx + 96) < 1.1 || Math.abs(lx + 132) < 1.1 || Math.abs(lx + 166) < 1.1;
      if (isCoreSpine) {
        out.c = lx > -135 ? WHITE : col(6);
      } else if (ringBand) {
        out.c = ly < 0 ? col(6) : col(3);
      } else if (effHalfW > 2.6 && Math.abs(ay - effHalfW * 0.54) < 0.6) {
        out.c = col(1);
      } else if (ly < 0) {
        out.c = isBevel ? col(6) : col(4);
      } else {
        out.c = isBevel ? col(3) : col(2);
      }
      out.a = alpha;
      return;
    }

    // Tail fin & glowing wake point
    if (isCoreSpine || lx < -194) {
      out.c = col(6);
    } else if (ly < 0) {
      out.c = col(5);
    } else {
      out.c = col(2);
    }
    out.a = alpha;
  });
}

export function create(o) {
  const C = fit(o.palette, 8);
  const R = new Rng(o.seed);
  const ps = new Particles({ rng: R, ground: () => 999, sparks: [WHITE, C[6], C[5], C[3]] });
  const lights = new Lights();

  // Stage 1 (0 ~ 0.22s): Converging light streaks pulling toward the cocked spear axis (-195 .. -52)
  for (let i = 0; i < 20; i++) {
    const delay = R.range(0, 0.12);
    const life = R.range(0.08, 0.11);
    const fromFront = i < 8;
    const targetX = fromFront ? R.range(-88, -42) : R.range(-195, -65);
    const startX = fromFront ? targetX + R.range(24, 48) : targetX + R.range(-14, 14);
    const side = i % 2 === 0 ? -1 : 1;
    const startY = AXIS_Y + side * R.range(16, 34);
    ps.spawn(
      P.STREAK,
      startX,
      startY,
      (targetX - startX) / life,
      (AXIS_Y - startY) / life,
      life,
      i % 3 === 0 ? WHITE : C[5],
      1.05,
      delay
    );
  }

  // Stage 1 (0.02 ~ 0.21s): Inward-spiraling condensation motes
  for (let i = 0; i < 16; i++) {
    const delay = R.range(0.02, 0.12);
    const life = R.range(0.08, 0.12);
    const tx = R.range(-192, -45);
    const sx = tx + R.range(-12, 22);
    const sy = AXIS_Y + R.sign() * R.range(14, 32);
    ps.spawn(
      P.MOTE,
      sx,
      sy,
      (tx - sx) / life,
      (AXIS_Y - sy) / life,
      life,
      R.pick([WHITE, C[6], C[5]]),
      0.85,
      delay
    );
  }

  // Stage 3 & 4 (0.35 ~ 0.90s): Tail-to-tip disintegration motes & rising golden embers
  for (let i = 0; i < 34; i++) {
    const posU = i / 34; // 0 = tail (-200), 1 = tip (-8)
    const x = -200 + posU * 192 + R.range(-6, 6);
    const y = AXIS_Y + R.range(-5, 5) * (1 - posU * 0.4);
    const delay = 0.35 + posU * 0.40 + R.range(-0.03, 0.03);
    const life = R.range(0.14, 0.23);
    const isEmber = i % 2 === 0;
    ps.spawn(
      isEmber ? P.EMBER : P.MOTE,
      x,
      y,
      R.range(-46, -14),
      R.range(-24, 6),
      life,
      R.pick([WHITE, C[6], C[5]]),
      isEmber ? R.range(0.8, 1.3) : 0.75,
      clamp(delay, 0.35, 0.74)
    );
  }

  // Stage 3 (0.35 ~ 0.65s): Fine sparks skimming backward along the spear surface
  for (let i = 0; i < 16; i++) {
    const x = R.range(-145, -14);
    const y = AXIS_Y + R.sign() * R.range(3, 7);
    const delay = R.range(0.35, 0.54);
    ps.spawn(
      P.SPARK,
      x,
      y,
      R.range(-145, -65),
      R.range(-12, 12),
      R.range(0.12, 0.22),
      WHITE,
      0.85,
      delay
    );
  }

  // Pre-seed 10 deterministic 3D helical light particles orbiting the shaft axis
  const helixOrbits = Array.from({ length: 10 }, (_, i) => ({
    x0: -182 + (i / 10) * 168,
    vx: R.range(-18, 10),
    rBase: R.range(6.5, 11.5),
    phase: (i / 10) * TAU + R.range(-0.3, 0.3),
    omega: R.range(9, 14) * (i % 2 === 0 ? 1 : -1),
    hot: i % 3 === 0,
  }));

  let t = 0;
  let burstTriggered = false;

  return {
    update(dt) {
      t += dt;
      // Stage 2 (0.22s): Thrust light burst — radii strictly bounded inside the 240x96 quad so no border clips!
      if (!burstTriggered && t >= FORM_T) {
        burstTriggered = true;
        lights.add(-12, AXIS_Y, 34, WHITE, 0.85, 0.15);
        lights.add(-34, AXIS_Y, 38, C[5], 0.75, 0.48);
        lights.add(-115, AXIS_Y, 36, C[4], 0.52, 0.52);
      }
      ps.update(dt);
      lights.update(dt);
    },

    draw(p) {
      if (t >= meta.duration - 0.01) return;

      // Compute current spear tip X:
      // 0..0.22s: condenses cocked back at tipX = -58 (grip/shaft anchored right by the caster)
      // 0.22..0.35s: rapid 58 px forward lunge to tipX = 0 (+4 px overshoot)
      // 0.35..0.90s: locked at tipX = 0 while eroding from tail to tip
      let tipX = 0;
      let form = 1;
      let alpha = 1;
      let whiteFlash = 0;
      let erode = 0;

      if (t < FORM_T) {
        const u = clamp(t / FORM_T);
        tipX = -58 - 10 * (1 - ease.outQuad(u));
        form = ease.inQuad(u);
        alpha = 0.3 + 0.7 * u;
      } else if (t < THRUST_END_T) {
        const u = clamp((t - FORM_T) / (THRUST_END_T - FORM_T));
        if (u < 0.55) {
          const lu = u / 0.55;
          tipX = -58 * (1 - ease.outCubic(lu)) + 4 * lu;
        } else {
          const su = (u - 0.55) / 0.45;
          tipX = 4 * (1 - su);
        }
        whiteFlash = t < FORM_T + 0.075 ? 1 : 0;
      } else if (t < DISINTEGRATE_T) {
        const u = clamp((t - THRUST_END_T) / (DISINTEGRATE_T - THRUST_END_T));
        tipX = 0;
        erode = u * 0.5;
      } else {
        const u = clamp((t - DISINTEGRATE_T) / 0.20);
        tipX = 0;
        erode = 0.5 + u * 0.5;
        alpha = clamp(1 - u);
      }

      // 1. Axial core light beam & bounded aura (kept strictly away from the 4 quad borders)
      if (t < 0.90) {
        const beamFade = t < FORM_T ? clamp(t / FORM_T) : t < DISINTEGRATE_T ? 1 : clamp(1 - (t - DISINTEGRATE_T) / 0.22);
        const startX = Math.max(-202, tipX - 196 * (1 - erode * 0.85));
        const endX = Math.min(14, tipX + (t >= FORM_T && t < 0.62 ? 10 * (1 - (t - FORM_T) / 0.4) : 0));

        p.line(startX, AXIS_Y, endX, AXIS_Y, (u) => (u > 0.65 ? WHITE : u > 0.3 ? C[6] : C[5]), beamFade * 0.92);
        p.glowLine(startX, AXIS_Y, endX, AXIS_Y, 5, C[5], 0.36 * beamFade);
        p.glow(Math.max(-175, tipX - 88 * (1 - erode * 0.5)), AXIS_Y, 34, C[4], 0.24 * beamFade);
        p.glow(Math.min(-2, tipX - 6), AXIS_Y, 22, C[6], (t >= FORM_T && t < 0.40 ? 0.68 : 0.35) * beamFade);
      }

      // 2. Back half of 3D orbital rings & 3D helical particles (z < 0, behind shaft)
      if (t >= 0.06 && t < 0.80) {
        const ringAlpha = t < FORM_T ? (t - 0.06) / (FORM_T - 0.06) : clamp(1 - (t - FORM_T) / 0.54);
        const scaleR = t < FORM_T ? 1.4 - 0.4 * ease.outQuad(t / FORM_T) : 1 + 0.3 * ((t - FORM_T) / 0.54);
        orbitalRing3D(p, tipX - 58, AXIS_Y, 3.8, 14.5 * scaleR, t * 14, C, ringAlpha, "back");
        if (erode < 0.38) {
          orbitalRing3D(p, tipX - 116, AXIS_Y, 3.2, 11 * scaleR, -t * 12 + 1.2, C, ringAlpha * 0.82, "back");
        }
      }

      if (t >= 0.08 && t < 0.84) {
        const orbAlpha = t < FORM_T ? (t - 0.08) / 0.14 : clamp(1 - (t - 0.52) / 0.32);
        for (const h of helixOrbits) {
          const hx = tipX + h.x0 + h.vx * Math.max(0, t - FORM_T);
          if (hx < -204 || hx > 16 || hx < tipX - 200 + erode * 195) continue;
          const ang = h.phase + t * h.omega;
          const z = Math.cos(ang);
          if (z < 0) {
            const rad = h.rBase * (t < FORM_T ? 1.5 - 0.5 * (t / FORM_T) : 1);
            const hy = AXIS_Y + Math.sin(ang) * rad;
            p.dot(hx, hy, h.hot ? C[4] : C[2], orbAlpha * 0.65);
          }
        }
      }

      // 3. Speed-ghost afterimages & thrust streak lines during the forward lunge (0.22 ~ 0.35s)
      if (t >= FORM_T && t < THRUST_END_T) {
        const u = (t - FORM_T) / (THRUST_END_T - FORM_T);
        for (let g = 2; g >= 1; g--) {
          const ghostX = tipX - g * 18 * (1 - u * 0.72);
          drawLance(p, ghostX, C, { alpha: (0.38 - g * 0.11) * (1 - u * 0.5), form: 1, whiteFlash: 0, erode: 0, tintStep: 1 });
        }
      }

      // 4. Main 3D-faceted golden lance body
      drawLance(p, tipX, C, { alpha, form, whiteFlash, erode, tintStep: 0 });

      // 5. Front half of 3D orbital rings & 3D helical particles (z >= 0, passing in front of shaft)
      if (t >= 0.06 && t < 0.80) {
        const ringAlpha = t < FORM_T ? (t - 0.06) / (FORM_T - 0.06) : clamp(1 - (t - FORM_T) / 0.54);
        const scaleR = t < FORM_T ? 1.4 - 0.4 * ease.outQuad(t / FORM_T) : 1 + 0.3 * ((t - FORM_T) / 0.54);
        orbitalRing3D(p, tipX - 58, AXIS_Y, 3.8, 14.5 * scaleR, t * 14, C, ringAlpha, "front");
        if (erode < 0.38) {
          orbitalRing3D(p, tipX - 116, AXIS_Y, 3.2, 11 * scaleR, -t * 12 + 1.2, C, ringAlpha * 0.82, "front");
        }
      }

      if (t >= 0.08 && t < 0.84) {
        const orbAlpha = t < FORM_T ? (t - 0.08) / 0.14 : clamp(1 - (t - 0.52) / 0.32);
        for (const h of helixOrbits) {
          const hx = tipX + h.x0 + h.vx * Math.max(0, t - FORM_T);
          if (hx < -204 || hx > 16 || hx < tipX - 200 + erode * 195) continue;
          const ang = h.phase + t * h.omega;
          const z = Math.cos(ang);
          if (z >= 0) {
            const rad = h.rBase * (t < FORM_T ? 1.5 - 0.5 * (t / FORM_T) : 1);
            const hy = AXIS_Y + Math.sin(ang) * rad;
            p.dot(hx, hy, h.hot ? WHITE : C[6], orbAlpha * 0.95);
            p.glow(hx, hy, 3.5, C[5], 0.22 * orbAlpha);
          }
        }
      }

      // 6. Particles & Stage 2 tip 8-point starburst flare
      ps.draw(p);

      if (t >= FORM_T && t < 0.50) {
        const fu = (t - FORM_T) / 0.28;
        const fk = Math.pow(1 - fu, 1.35);
        tipFlare(p, tipX, AXIS_Y, 20 * (1 - fu * 0.35), fk, C);
        if (fu < 0.55) {
          p.glowRing(tipX, AXIS_Y, 5 + 14 * ease.outCubic(fu / 0.55), 3, C[6], 0.38 * (1 - fu / 0.55));
        }
      }

      lights.draw(p);
    },
  };
}
