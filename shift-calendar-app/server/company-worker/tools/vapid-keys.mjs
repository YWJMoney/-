// [ADMIN-19] Makes a VAPID key pair for web push to the developer app: `node tools/vapid-keys.mjs`.
// Put the two lines into the worker's secrets (Settings → Variables and Secrets, type Secret), never into git.
const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const b64 = (bytes) => Buffer.from(bytes).toString("base64url");
const pub = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
const { d } = await crypto.subtle.exportKey("jwk", pair.privateKey);
console.log(`VAPID_PUBLIC_KEY=${b64(pub)}`);
console.log(`VAPID_PRIVATE_KEY=${d}`);
