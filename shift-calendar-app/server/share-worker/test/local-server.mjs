// [MKT-09] The worker on a plain Node server with an in-memory KV, for the app's round-trip test:
//   node test/local-server.mjs 8787 &
//   SHARE_TEST_URL=http://127.0.0.1:8787 ./gradlew :app:testDebugUnitTest --tests '*ShareLinkTest*'
import http from "node:http";
import worker from "../src/index.js";

const map = new Map();
const env = {
  RATE_SALT: "local",
  SHARES: {
    async get(key, type) {
      const v = map.get(key);
      return v === undefined ? null : type === "json" ? JSON.parse(v) : v;
    },
    async put(key, value) { map.set(key, value); },
    async delete(key) { map.delete(key); },
  },
};

const port = Number(process.argv[2] || 8787);
http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) if (typeof v === "string") headers.set(k, v);
  headers.set("cf-connecting-ip", req.socket.remoteAddress || "unknown");
  const body = ["GET", "HEAD"].includes(req.method) ? undefined : Buffer.concat(chunks);
  const out = await worker.fetch(new Request(`http://${req.headers.host}${req.url}`, { method: req.method, headers, body }), env);
  res.writeHead(out.status, Object.fromEntries(out.headers));
  res.end(Buffer.from(await out.arrayBuffer()));
}).listen(port, "127.0.0.1", () => console.log(`share worker on http://127.0.0.1:${port}`));
