({
  project: Project.name,
  format: Format.id,
  elements: Cube.all.length,
  groups: Group.all.length,
  textures: Texture.all.map(t => t.name),
  animations: (typeof Animation !== 'undefined' ? Animation.all.map(a => a.name) : []),
  selected: (typeof selected !== 'undefined' && selected ? selected.map(e => e.name) : []),
  mode: Mode.selected ? Mode.selected.id : null,
  outlinerRoots: Outliner.root.length
})
