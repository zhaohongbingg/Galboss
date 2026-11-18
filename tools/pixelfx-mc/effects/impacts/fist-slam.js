import { Particles, Bits, Lights, P, B, Rng, fit, clamp, ease, TAU, WHITE } from "../../core/index.js";

export const meta = {
  name: "Fist slam",
  desc: "A giant golden gauntleted fist slams diagonally down into the ground in a rapid burst, sending out two low, fast shockwaves, flying splinters and rising golden embers.",
  tags: ["impact", "fist", "slam", "punch", "gold", "ground", "one-shot"],
  size: [208, 176],
  origin: [104, 164],
  duration: 0.9,
  loop: false,
  fps: 20,
  bg: "ground",
  options: {
    palette: ["#3b1d2a", "#7a3a2c", "#c4652e", "#f0a040", "#ffd25e", "#fff0a0", "#fffbe0", "#ffffff"],
    seed: 11,
  },
};

const IMPACT_T = 0.12;
const START_X = 72;
const START_Y = -126;

// Low, flat tilted ground shockwave ring (sy ~ 0.13)
function flatShockRing(p, cx, cy, rx, ry, hot, a, C) {
  if (rx < 1 || a <= 0.02) return;
  const n = Math.ceil(rx * TAU * 1.6) + 12;
  for (let i = 0; i < n; i++) {
    const g = (i / n) * TAU;
    const near = Math.sin(g) >= 0;
    const px = cx + Math.cos(g) * rx;
    const py = cy + Math.sin(g) * ry;
    p.dot(px, py, hot > 0.5 ? WHITE : near ? C[6] : C[4], a * (near ? 1 : 0.68));
    p.dot(px, py - 1, near ? C[3] : C[1], a * 0.6);
  }
}

// Clenched golden fist (~40 x 36 px) + short forearm cuff; anchor (cx, cy) = bottom knuckle center (no body)
function drawFist(p, cx, cy, C, alpha, whiteFlash, tintStep = 0) {
  if (alpha <= 0.02) return;
  const col = (idx) => C[clamp(idx + tintStep, 0, 7)];

  // 1. Short forearm cuff angled up-right from (5, -28) to (24, -50)
  const ax0 = cx + 5;
  const ay0 = cy - 28;
  const ax1 = cx + 24;
  const ay1 = cy - 50;
  const vx = ax1 - ax0;
  const vy = ay1 - ay0;
  const vLen = Math.hypot(vx, vy);
  const ux = vx / vLen;
  const uy = vy / vLen;

  p.shade(ax0 - 14, ay1 - 14, ax1 + 14, ay0 + 6, (x, y, out) => {
    const dx = x - ax0;
    const dy = y - ay0;
    const along = dx * ux + dy * uy;
    if (along < -2 || along > vLen + 1) return;
    const perp = -dx * uy + dy * ux;
    const halfW = 9.5 + (along / vLen) * 1.5;
    if (Math.abs(perp) > halfW + 1) return;

    if (whiteFlash > 0.5) {
      out.c = Math.abs(perp) > halfW ? C[6] : WHITE;
      out.a = alpha;
      return;
    }
    if (Math.abs(perp) > halfW || along > vLen) {
      out.c = col(0);
    } else if (along > vLen - 3) {
      // Cuff rim band
      out.c = perp < 0 ? col(6) : col(4);
    } else if (perp < -halfW + 1.8) {
      out.c = col(5);
    } else if (perp > halfW - 2.2) {
      out.c = col(1);
    } else {
      out.c = perp < 0 ? col(4) : col(3);
    }
    out.a = alpha;
  });

  // 2. Clenched fist gauntlet: 40 x 34 px (x: -20..20, y: -34..0)
  const hw = 20;
  const hh = 34;
  p.shade(cx - hw - 3, cy - hh - 1, cx + hw + 1, cy + 1, (x, y, out) => {
    const lx = x - cx;
    const ly = y - cy;

    // Tucked thumb plate on front-left (-22 <= lx <= -11, -21 <= ly <= -8)
    const inThumb = lx >= -22 && lx <= -11 && ly >= -21 && ly <= -8 && Math.abs(lx + 16.5) + Math.abs(ly + 14.5) <= 10.5;
    // Main fist block (-19 <= lx <= 19, -34 <= ly <= 0) with rounded/chamfered upper corners
    const inMain = lx >= -19 && lx <= 19 && ly >= -34 && ly <= 0 && (ly > -28 || Math.abs(lx) <= 19 - (-28 - ly) * 0.65);

    if (!inMain && !inThumb) return;

    if (whiteFlash > 0.5) {
      out.c = ly >= -2 || lx <= -18 || lx >= 18 ? C[6] : WHITE;
      out.a = alpha;
      return;
    }

    if (inThumb && lx < -18) {
      const tEdge = lx <= -21 || ly <= -20 || ly >= -9;
      out.c = tEdge ? col(0) : lx <= -19.5 ? col(6) : col(3);
      out.a = alpha;
      return;
    }

    const outer = lx <= -18.2 || lx >= 18.2 || ly <= -33.2 || ly >= -0.8 || (ly <= -28 && Math.abs(lx) >= 18.2 - (-28 - ly) * 0.65);
    if (outer) {
      out.c = col(0);
      out.a = alpha;
      return;
    }

    // Bottom striking knuckles (y: -9..0) divided into 4 distinct finger blocks
    if (ly >= -9) {
      const knuckleGroove = Math.abs(lx + 9.5) < 0.65 || Math.abs(lx) < 0.65 || Math.abs(lx - 9.5) < 0.65;
      if (knuckleGroove) {
        out.c = col(1);
      } else if (ly >= -2.2) {
        // White-gold striking face on the bottom knuckles
        out.c = col(6);
      } else if (ly <= -8) {
        out.c = col(2);
      } else {
        out.c = lx < 0 ? col(5) : col(4);
      }
      out.a = alpha;
      return;
    }

    // Upper gauntlet plate + central diamond crest
    const crest = Math.abs(lx) + Math.abs(ly + 21) <= 4.5;
    if (crest) {
      out.c = Math.abs(lx) + Math.abs(ly + 21) <= 2 ? WHITE : col(6);
    } else if (lx <= -16.5 || ly <= -31.5) {
      out.c = col(6);
    } else if (lx >= 15.5) {
      out.c = col(1);
    } else {
      out.c = ly < -21 ? col(4) : col(3);
    }
    out.a = alpha;
  });

  if (tintStep === 0) {
    p.glow(cx, cy - 18, 18, C[5], 0.36 * alpha);
  }
}

export function create(o) {
  const C = fit(o.palette, 8);
  const R = new Rng(o.seed);
  const floor = () => 0;
  const ps = new Particles({ rng: R, ground: floor, sparks: [WHITE, C[6], C[5], C[3]] });
  const bits = new Bits({ ground: floor });
  const lights = new Lights();

  // Stage 1 (0 ~ 0.12s): Fast diagonal speed streaks along the incoming fist path
  for (let i = 0; i < 10; i++) {
    const f = i / 10;
    const off = R.range(-22, 22);
    const sx = START_X * (1 - f * 0.45) + off * 0.6;
    const sy = START_Y * (1 - f * 0.45) - off * 0.4;
    ps.spawn(P.STREAK, sx, sy, -START_X * 7.5, -START_Y * 7.5, 0.08, i % 2 === 0 ? WHITE : C[5], 1.1, f * 0.04);
  }

  let t = 0;
  let impacted = false;

  function triggerImpact() {
    impacted = true;

    // Stage 2: Punchy golden-white ground light burst
    lights.add(0, -10, 98, C[5], 0.92, 0.44);
    lights.add(0, -4, 42, WHITE, 0.95, 0.12);
    lights.add(0, 0, 72, C[4], 0.5, 0.52);

    // Stage 3: Low, fast golden & stone splinters
    for (let i = 0; i < 10; i++) {
      const side = i % 2 === 0 ? -1 : 1;
      const ang = -Math.PI * 0.5 + side * R.range(0.55, 1.38);
      const spd = R.range(115, 260);
      bits.add({
        kind: B.SHARD,
        x: side * R.range(4, 18),
        y: -3,
        vx: Math.cos(ang) * spd,
        vy: Math.sin(ang) * spd - 22,
        g: 0.9,
        drag: 0.6,
        bounce: 0.34,
        life: R.range(0.36, 0.58),
        a: R.range(0, TAU),
        va: R.range(-26, 26),
        s: R.range(2, 3.8),
        c: R.pick([C[6], C[5], C[4], C[3]]),
      });
    }

    // Stage 3: Flat outward spark spray skimming the floor
    for (let i = 0; i < 24; i++) {
      const side = i % 2 === 0 ? -1 : 1;
      const ang = -Math.PI * 0.5 + side * R.range(0.75, 1.48);
      const spd = R.range(150, 350);
      ps.spawn(
        P.SPARK,
        side * R.range(6, 22),
        -2,
        Math.cos(ang) * spd,
        Math.sin(ang) * spd - 12,
        R.range(0.16, 0.38),
        WHITE,
        1,
        R.range(0, 0.02)
      );
    }

    // Stage 3: Low ground-hugging dust jets
    for (let i = 0; i < 14; i++) {
      const side = i % 2 === 0 ? -1 : 1;
      ps.spawn(
        P.DUST,
        side * R.range(10, 26),
        -2,
        side * R.range(65, 175),
        R.range(-18, -4),
        R.range(0.26, 0.46),
        R.pick([C[4], C[3], C[2]]),
        R.range(3, 5.5),
        R.range(0, 0.03)
      );
    }

    // Stage 4: Rising golden embers & motes fading cleanly before t = 0.85s
    for (let i = 0; i < 14; i++) {
      ps.spawn(
        P.EMBER,
        R.range(-42, 42),
        R.range(-2, -8),
        R.range(-20, 20),
        R.range(-75, -32),
        R.range(0.32, 0.56),
        R.pick([WHITE, C[6], C[5]]),
        R.range(0.8, 1.3),
        R.range(0.04, 0.18)
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

      // Stage 3 (0.12 ~ 0.44s): Two low, fast expanding ground shockwaves
      if (t >= IMPACT_T && t < 0.44) {
        const su = (t - IMPACT_T) / 0.25;
        if (su < 1) {
          const r1 = 12 + 82 * ease.outCubic(su);
          const a1 = su < 0.18 ? 1 : clamp(1 - (su - 0.18) / 0.82);
          flatShockRing(p, 0, 0, r1, r1 * 0.13, su < 0.2 ? 1 : 0, a1, C);
          p.glowRing(0, 0, r1 * 0.85, 4, C[5], 0.34 * a1 * (1 - su));
        }

        const su2 = (t - (IMPACT_T + 0.04)) / 0.28;
        if (su2 > 0 && su2 < 1) {
          const r2 = 8 + 56 * ease.outCubic(su2);
          flatShockRing(p, 0, 0, r2, r2 * 0.13, 0, (1 - su2) * 0.82, C);
        }
      }

      bits.draw(p);
      ps.draw(p);

      // Fist silhouette: slams diagonally down-left (0 ~ 0.12s) with 2 ghosts, holds white-hot, dissolves by 0.28s
      if (t < 0.28) {
        if (t < IMPACT_T) {
          const u = clamp(t / IMPACT_T);
          const k = ease.inQuad(u);
          const fx = START_X * (1 - k);
          const fy = START_Y * (1 - k);

          // 2 trailing ghost afterimages along the diagonal slam vector
          for (let g = 2; g >= 1; g--) {
            const gu = clamp((t - g * 0.024) / IMPACT_T);
            if (gu > 0) {
              const gk = ease.inQuad(gu);
              drawFist(p, START_X * (1 - gk), START_Y * (1 - gk), C, (0.36 - g * 0.1) * u, 0, 1);
            }
          }

          drawFist(p, fx, fy, C, 0.8 + 0.2 * u, 0, 0);
          p.glowLine(fx + 10, fy - 16, fx + 42, fy - 70, 5, C[5], 0.32 * u);
        } else {
          const post = t - IMPACT_T;
          const whiteFlash = post < 0.055 ? 1 : 0;
          const fadeOut = post < 0.07 ? 1 : clamp(1 - (post - 0.07) / 0.09);
          drawFist(p, 0, 0, C, fadeOut, whiteFlash, 0);
        }
      }

      // Stage 2 (0.12 ~ 0.28s): Compact horizontal ground flash & burst
      if (t >= IMPACT_T && t < 0.28) {
        const fu = (t - IMPACT_T) / 0.16;
        const fk = 1 - fu;
        const span = 56 * (1 - fu * 0.35);
        p.line(-span, -1, span, -1, (u) => (Math.abs(u - 0.5) < 0.2 ? WHITE : C[6]), fk);
        p.glowLine(-span, -1, span, -1, 5, C[6], 0.6 * fk);
        p.glow(0, -4, 48, C[5], 0.55 * fk);
        if (fu < 0.45) {
          p.glow(0, -3, 20, WHITE, 0.85 * (1 - fu / 0.45));
        }
      }

      lights.draw(p);
    },
  };
}
