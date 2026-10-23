// [ADMIN-03] One worker.js to paste into the Cloudflare dashboard: src/index.js with every file it imports
// from src/ (the developer app src/admin-app.js, its helpers src/admin-lib.js and [ADMIN-19] web push
// src/push.js) put in place of the import lines, each once.
//   npm run bundle            -> dist/worker.js
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const IMPORT = /^import \{ [\w, ]+ \} from "\.\/([\w-]+\.js)";$/gm;

export function bundle() {
  const index = readFileSync(join(root, "src/index.js"), "utf8");
  if (!index.includes('import { adminFile, adminBuild } from "./admin-app.js";')) {
    throw new Error("src/index.js no longer imports the developer app the expected way");
  }
  const done = new Set();
  const inline = (code) => code.replace(IMPORT, (_, file) => {
    if (done.has(file)) return "";
    done.add(file);
    const body = inline(readFileSync(join(root, "src", file), "utf8")).replace(/^export (async function|function|const) /gm, "$1 ");
    return `// ---- src/${file} ----\n${body}\n// ---- end of src/${file} ----`;
  });
  const out = inline(index);
  if (/^\s*import\s/m.test(out)) throw new Error("an import is left that the bundle cannot follow");
  return out;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const out = join(root, "dist/worker.js");
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, bundle());
  console.log(`wrote ${out}`);
}
