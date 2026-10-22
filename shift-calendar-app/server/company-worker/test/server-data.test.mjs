// [SRV-03] docs/server-data.md lists every table the company server makes and every key the share server
// writes, so a new table cannot go out without its reason and its deletion. `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");
const doc = read("../../../docs/server-data.md");
/** The first cell of each table row in the document, without its backticks. */
const listed = new Set([...doc.matchAll(/^\| `([^`]+)`/gm)].map((m) => m[1]));

test("[SRV-03] every table in the company server's SCHEMA has a line in docs/server-data.md", () => {
  const src = read("../src/index.js");
  const schema = src.slice(src.indexOf("const SCHEMA = ["), src.indexOf("const COLUMNS = ["));
  const tables = [...schema.matchAll(/CREATE TABLE IF NOT EXISTS (\w+)/g)].map((m) => m[1]);
  assert.ok(tables.length > 30, "found the tables");
  const missing = tables.filter((t) => !listed.has(t));
  assert.deepEqual(missing, [], "docs/server-data.md needs a line for these tables");
  // And no line for a table that is not there any more (the old sessions table is dropped).
  const gone = [...listed].filter((t) => /^[a-z_]+$/.test(t) && !tables.includes(t));
  assert.deepEqual(gone, [], "lines for tables the server no longer makes");
});

test("[SRV-03] every kind of key the share server writes has a line in docs/server-data.md", () => {
  const src = read("../../share-worker/src/index.js");
  const prefixes = new Set([...src.matchAll(/[`"](rl|rr|rp|report|stats):/g)].map((m) => m[1]));
  // allow(env, request, "rr", …) builds its keys from the prefix it is given.
  for (const m of src.matchAll(/allow\(env, request, "(\w+)"/g)) prefixes.add(m[1]);
  assert.ok(prefixes.has("report") && prefixes.has("stats") && prefixes.has("rp"));
  for (const p of prefixes) assert.ok(doc.includes(`\`${p}:`), `docs/server-data.md needs the ${p}: keys`);
  assert.ok(doc.includes("`<링크 id>`"), "the links themselves");
});
