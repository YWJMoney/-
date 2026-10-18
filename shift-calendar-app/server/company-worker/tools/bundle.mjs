// [ADMIN-03] One worker.js to paste into the Cloudflare dashboard: src/index.js with the developer app
// (src/admin-app.js, which imports nothing) put in place of its import line.
//   npm run bundle            -> dist/worker.js
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const IMPORT = 'import { adminFile } from "./admin-app.js";';

export function bundle() {
  const index = readFileSync(join(root, "src/index.js"), "utf8");
  const app = readFileSync(join(root, "src/admin-app.js"), "utf8");
  if (!index.includes(IMPORT)) throw new Error("src/index.js no longer imports the developer app the expected way");
  if (/^\s*import\s/m.test(app)) throw new Error("src/admin-app.js must not import anything");
  const inlined = app.replace(/^export function /gm, "function ");
  return index.replace(IMPORT, () => `// ---- the developer app (src/admin-app.js) ----\n${inlined}\n// ---- end of the developer app ----`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const out = join(root, "dist/worker.js");
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, bundle());
  console.log(`wrote ${out}`);
}
