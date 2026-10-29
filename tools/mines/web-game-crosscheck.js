// The practice game's half of WebGameCrossCheckTest.
//
// Open https://activate.ryflix.ca/mines.html, paste this into the browser console, and
// it prints the block hashes the test holds. It drives the game's own functions --
// resetLevel, onCellClick, detonate -- on boards it builds from a fixed RNG, so the
// only thing changed is the 36 ms animation step, which it takes out so a thousand
// boards run in half a minute. Ties between rays are untouched: the game settles them
// in the order the bombs were clicked, step by step, with or without the delay.
//
// If the game changes and the hashes no longer match, this is how to find out which
// way: the test prints its own counts of losses and second detonations next to the
// ones below.
(async () => {
  sleep = () => Promise.resolve();
  const xs = (seed) => { let s = seed >>> 0; return { int(n) { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return s % n; } }; };
  const fnv = (str) => { let h = 0x811c9dc5; for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; } return h.toString(16).padStart(8, '0'); };
  const W = 16, C0 = 20, hashes = [];
  let lost = 0, secondRounds = 0;
  for (let i = 0; i < 1000; i++) {
    const g = xs(1000 + i);
    const killPer200 = (i % 2 === 0) ? 0 : 4;
    const normal = [], armored = [], kill = [];
    for (let r = 0; r < 7; r++) for (let c = 0; c < W; c++) {
      const v = g.int(200);
      if (v < 28) normal.push([r, c + C0]); else if (v < 40) armored.push([r, c + C0]); else if (v < 40 + killPer200) kill.push([r, c + C0]);
    }
    const R = 1 + g.int(2);
    BOMB_BUDGET = 999;
    currentPuzzle = { bombs: [], normalMines: normal, armoredMines: armored, killSquares: kill };
    resetLevel();
    const sig = [];
    for (let round = 0; round < R; round++) {
      if (!Object.values(mineHp).some(h => h > 0)) break;
      const cand = [];
      for (let r = 0; r < 7; r++) for (let c = 0; c < W; c++) { const k = r + ',' + (c + C0); if (!(mineHp[k] > 0) && !killSet.has(k)) cand.push([r, c + C0]); }
      if (cand.length === 0) break;
      const nb = 1 + g.int(4);
      for (let b = 0; b < nb && cand.length > 0; b++) { const idx = g.int(cand.length); const [r, c] = cand.splice(idx, 1)[0]; onCellClick(r, c); }
      await detonate();
      if (round === 1) secondRounds++;
      if (document.getElementById('status').textContent.includes('KILL')) { sig.push('L'); lost++; break; }
      let s = '';
      for (let r = 0; r < 7; r++) for (let c = 0; c < W; c++) { const k = r + ',' + (c + C0); s += (k in mineHp) ? String(mineHp[k]) : '.'; }
      sig.push(s);
    }
    hashes.push(fnv(sig.join('|')));
  }
  const blocks = [];
  for (let k = 0; k < 20; k++) blocks.push(fnv(hashes.slice(k * 50, k * 50 + 50).join(' ')));
  console.log({ lost, secondRounds, all: fnv(hashes.join(' ')), blocks: blocks.join(' ') });
})();
