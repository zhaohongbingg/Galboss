(function () {
	if (typeof SPEC === 'undefined' || !SPEC) return { error: 'SPEC not injected' };

	Undo.initEdit({ outliner: true, elements: [], selection: true });

	var tex = Texture.all.filter(function (t) { return t.name === SPEC.texture_name; })[0] || Texture.all[0];
	if (!tex) {
		Undo.cancelEdit();
		return { error: 'no texture in project' };
	}

	function findGroup(name) {
		var hits = Group.all.filter(function (g) { return g.name === name; });
		return hits.length ? hits[0] : null;
	}
	function findCube(name) {
		var hits = Cube.all.filter(function (c) { return c.name === name; });
		return hits.length ? hits[0] : null;
	}

	var report = { created_groups: [], created_cubes: [], problems: [] };

	function makeGroup(spec) {
		var g = findGroup(spec.name);
		if (!g) {
			g = new Group({ name: spec.name, origin: spec.pivot, visibility: true, autouv: 0 }).init();
			report.created_groups.push(spec.name);
		}
		g.origin = spec.pivot.slice();
		g.rotation = (spec.rotation || [0, 0, 0]).slice();
		// addTo('root') 里的 'root' 是**字符串**（大纲根节点），不是名为 root 的 Group。
		// v1 写成 `target = 'root'` 且 spec.parent === 'root' 时进不了下面的 findGroup 分支，
		// 于是 body / right_leg / left_leg 全被拍平到顶层：导出的 geo 里三者的 parent 都是空，
		// root 组一个子骨骼都没有 —— 表现是转 root 完全没反应（hurt / phase 因此是空动画），
		// 转 body 只带动躯干、腿留在原地（腰部裂开）。这里改成**真的去找同名的 root 组**。
		var target = null;
		if (spec.parent) {
			target = findGroup(spec.parent);
			if (!target) report.problems.push('group ' + spec.name + ': missing parent ' + spec.parent);
		}
		g.addTo(target || 'root');
		return g;
	}

	function rectOf(key) {
		if (Object.prototype.toString.call(key) === '[object Array]') return { uv: [key[0], key[1]], size: [key[2], key[3]] };
		var src = SPEC.uv_sources[key];
		if (!src) throw new Error('unknown uv source: ' + key);
		return src;
	}

	function makeCube(spec) {
		var parent = findGroup(spec.parent);
		if (!parent) {
			report.problems.push('cube ' + spec.name + ': missing parent ' + spec.parent);
			return null;
		}
		var cube = findCube(spec.name);
		if (!cube) {
			cube = new Cube({ name: spec.name, from: spec.from, to: spec.to, origin: spec.pivot, autouv: 0 }).init();
			report.created_cubes.push(spec.name);
		}
		cube.from = spec.from.slice();
		cube.to = spec.to.slice();
		cube.origin = (spec.pivot || spec.from).slice();
		cube.rotation = (spec.rotation || [0, 0, 0]).slice();
		cube.autouv = 0;
		if (typeof cube.box_uv !== 'undefined') cube.box_uv = false;
		if (typeof cube.inflate !== 'undefined') cube.inflate = spec.inflate || 0;
		cube.addTo(parent);

		var faceMap = spec.faces || {};
		['north', 'south', 'east', 'west', 'up', 'down'].forEach(function (fname) {
			var face = cube.faces[fname];
			if (!face) return;
			var key = faceMap.hasOwnProperty(fname) ? faceMap[fname] : faceMap.all;
			if (key === false || key === null || typeof key === 'undefined') {
				face.texture = null;
				return;
			}
			var r = rectOf(key);
			face.texture = tex.uuid;
			face.uv = [r.uv[0], r.uv[1]];
			face.uv_size = [r.size[0], r.size[1]];
		});
		return cube;
	}

	(SPEC.groups || []).forEach(makeGroup);
	(SPEC.cubes || []).forEach(makeCube);

	Canvas.updateAll();
	Undo.finishEdit('Build boss rig: ' + SPEC.name);
	Project.saved = false;

	return {
		project: Project.name,
		uv_mode: Project.uv_mode,
		problems: report.problems,
		groups: Group.all.map(function (g) { return g.name + ' pivot=' + JSON.stringify(g.origin) + ' parent=' + (g.parent ? g.parent.name : 'null'); }),
		cubes: Cube.all.map(function (c) { return c.name + ' from=' + JSON.stringify(c.from) + ' to=' + JSON.stringify(c.to) + ' parent=' + c.parent.name; })
	};
})()
