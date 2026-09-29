(function () {
	var out = { textures: {}, weapons: {} };
	var names = ['reizein_tohka', 'onabuta_ikuko', 'tadasugawa_rei'];
	names.forEach(function (n) {
		var p = null;
		ModelProject.all.forEach(function (q) { if (q.name === n && !p) p = q; });
		if (!p) { out.textures[n] = 'missing'; return; }
		p.select();
		var t = Texture.all[0];
		out.textures[n] = (t && t.canvas && t.canvas.toDataURL) ? t.canvas.toDataURL('image/png') : 'no-canvas';
		var w = Group.all.filter(function (g) { return g.name === 'gun' || g.name === 'katana'; })[0];
		if (w) {
			out.weapons[n] = {
				bone: { name: w.name, pivot: w.origin, rotation: w.rotation, parent: w.parent ? w.parent.name : null },
				cubes: Cube.all.filter(function (c) { return c.parent && c.parent.name === w.name; }).map(function (c) {
					return { name: c.name, from: c.from, to: c.to, pivot: c.origin };
				})
			};
		}
	});
	return out;
})()
