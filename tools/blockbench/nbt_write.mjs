// NBT 写入器（大端），只覆盖结构 template 需要的类型：byte/int/string/list/compound。

export class W {
  constructor() { this.parts = []; }

  raw(b) { this.parts.push(b); }

  u8(v) { this.raw(Buffer.from([v])); }

  i32(v) { const b = Buffer.alloc(4); b.writeInt32BE(v); this.raw(b); }

  str(s) {
    const b = Buffer.from(s, 'utf8');
    const h = Buffer.alloc(2);
    h.writeUInt16BE(b.length);
    this.raw(Buffer.concat([h, b]));
  }

  tag(type, name) { this.u8(type); this.str(name); }

  int(name, v) { this.tag(3, name); this.i32(v); }

  string(name, v) { this.tag(8, name); this.str(v); }

  /** 开始一个 list；count 必须是已知的（结构里的 size / blocks / palette 都能先算出来）。 */
  list(name, elemType, count) {
    this.tag(9, name);
    this.u8(elemType);
    this.i32(count);
  }

  // list 里的 compound 元素：只写字段（无类型名、无名字），最后用 end() 收尾。
  // list 里的 int 元素：直接 i32(value)。

  compound(name) { this.tag(10, name); }

  /**
   * 递归写一个「带类型的值」（typed 读取器读出来的 {t, v}），用于把方块实体数据原样搬进结构。
   * 注意 compound 的 payload 就是「字段 + 0 结尾」，所以 list 元素（不带名字/类型头）也走这里。
   */
  value(node) {
    const { t, v } = node;
    switch (t) {
      case 1: this.u8(v & 0xff); break;
      case 2: { const b = Buffer.alloc(2); b.writeInt16BE(v); this.raw(b); break; }
      case 3: this.i32(v); break;
      case 4: { const b = Buffer.alloc(8); b.writeBigInt64BE(v); this.raw(b); break; }
      case 5: { const b = Buffer.alloc(4); b.writeFloatBE(v); this.raw(b); break; }
      case 6: { const b = Buffer.alloc(8); b.writeDoubleBE(v); this.raw(b); break; }
      case 7: { const h = Buffer.alloc(4); h.writeInt32BE(v.length); this.raw(Buffer.concat([h, v])); break; }
      case 8: this.str(v); break;
      case 9: this.listValue(v.length ? v[0].t : 0, v); break;
      case 10: {
        for (const k of Object.keys(v)) { this.tag(v[k].t, k); this.value(v[k]); }
        this.end();
        break;
      }
      case 11: {
        const h = Buffer.alloc(4); h.writeInt32BE(v.length); this.raw(h);
        for (const n of v) this.i32(n);
        break;
      }
      case 12: {
        const h = Buffer.alloc(4); h.writeInt32BE(v.length); this.raw(h);
        for (const n of v) { const b = Buffer.alloc(8); b.writeBigInt64BE(n); this.raw(b); }
        break;
      }
      default: throw new Error('写不出的 NBT 类型 ' + t);
    }
  }

  listValue(elemType, arr) {
    this.u8(elemType);
    const h = Buffer.alloc(4); h.writeInt32BE(arr.length); this.raw(h);
    for (const e of arr) this.value(e);
  }

  end() { this.u8(0); }

  build() { return Buffer.concat(this.parts); }
}
