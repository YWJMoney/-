// [COMM-04] The D1 calls the worker makes, on Node's built-in SQLite, so tests run without Cloudflare.
import { DatabaseSync } from "node:sqlite";

export function d1(path = ":memory:") {
  const db = new DatabaseSync(path);
  const check = (params) => params.map((p) => {
    // D1 refuses undefined; booleans become 1 and 0 there, so the worker binds numbers.
    if (p === undefined) throw new TypeError("D1_TYPE_ERROR: undefined is not supported");
    if (typeof p === "boolean") throw new TypeError("bind 0/1, not a boolean");
    return p;
  });
  const statement = (sql, params = []) => ({
    sql,
    bind: (...values) => statement(sql, check(values)),
    async first(column) {
      const row = db.prepare(sql).get(...params);
      if (row === undefined) return null;
      return column ? row[column] : { ...row };
    },
    async all() {
      return { success: true, results: db.prepare(sql).all(...params).map((r) => ({ ...r })), meta: {} };
    },
    async run() {
      const r = db.prepare(sql).run(...params);
      return { success: true, results: [], meta: { changes: Number(r.changes), last_row_id: Number(r.lastInsertRowid) } };
    },
  });
  return {
    db,
    prepare: (sql) => statement(sql),
    async batch(list) {
      db.exec("BEGIN");
      try {
        const out = [];
        for (const s of list) out.push(/^\s*(SELECT|WITH)/i.test(s.sql) ? await s.all() : await s.run());
        db.exec("COMMIT");
        return out;
      } catch (e) {
        db.exec("ROLLBACK");
        throw e;
      }
    },
    /** Every text stored anywhere, for checks that something is never kept. */
    dump() {
      const tables = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((t) => t.name);
      return tables.map((t) => JSON.stringify(db.prepare(`SELECT * FROM "${t}"`).all())).join("\n");
    },
  };
}
