(function () {
  const fs = require('fs');
  const p = 'D:/game/mc/bossmod/tools/blockbench/_preview/beatrice-view.bbmodel';
  const buf = fs.readFileSync(p);
  const file = new File([new Uint8Array(buf)], 'beatrice-view.bbmodel');
  let err = null;
  try {
    Blockbench.import({ extensions: ['bbmodel'], name: 'beatrice-view.bbmodel' }, [file]);
  } catch (e) {
    err = String((e && e.message) || e);
  }
  return { importCalled: err === null, err };
})()
