// [ADMIN-19] Web push to the developer app, with nothing but WebCrypto: the message is encrypted for the
// browser that asked for it (RFC 8291, aes128gcm) and the request is signed with the server's VAPID key
// (RFC 8292, an ES256 JWT), so the push service in between reads neither. Only when the secrets
// VAPID_PUBLIC_KEY (65 bytes, base64url) and VAPID_PRIVATE_KEY (32 bytes, base64url) are set; VAPID_SUBJECT
// (mailto: or https:) is optional.

const pushEnc = new TextEncoder();

export const pushB64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

export function pushUnb64(s) {
  if (typeof s !== "string" || !/^[A-Za-z0-9_-]*$/.test(s)) return null;
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

const pushJoin = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
};

async function pushHmac(key, data) {
  const k = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, data));
}

/** The server's keys when both secrets are set and fit together in shape; null otherwise. */
export function pushReady(env) {
  const pub = pushUnb64(typeof env.VAPID_PUBLIC_KEY === "string" ? env.VAPID_PUBLIC_KEY.trim() : null);
  const priv = pushUnb64(typeof env.VAPID_PRIVATE_KEY === "string" ? env.VAPID_PRIVATE_KEY.trim() : null);
  if (!pub || pub.length !== 65 || pub[0] !== 4 || !priv || priv.length !== 32) return null;
  const subject = typeof env.VAPID_SUBJECT === "string" && /^(mailto:|https:\/\/)\S+$/.test(env.VAPID_SUBJECT.trim())
    ? env.VAPID_SUBJECT.trim() : "mailto:admin@bamnat.invalid";
  return { publicKey: pushB64(pub), pub, priv, subject };
}

/** RFC 8292: a JWT for the push service at [endpoint], good for 12 hours. */
export async function vapidAuth(keys, endpoint, now) {
  const key = await crypto.subtle.importKey("jwk", {
    kty: "EC", crv: "P-256", d: pushB64(keys.priv), x: pushB64(keys.pub.slice(1, 33)), y: pushB64(keys.pub.slice(33, 65)), ext: true,
  }, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const head = pushB64(pushEnc.encode(JSON.stringify({ typ: "JWT", alg: "ES256" })));
  const claims = pushB64(pushEnc.encode(JSON.stringify({ aud: new URL(endpoint).origin, exp: Math.floor(now / 1000) + 12 * 3600, sub: keys.subject })));
  // WebCrypto signs ECDSA as r || s, which is what JWS wants.
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, pushEnc.encode(`${head}.${claims}`));
  return `vapid t=${head}.${claims}.${pushB64(sig)}, k=${keys.publicKey}`;
}

/** RFC 8291: [payload] encrypted for one browser's p256dh and auth keys, as one aes128gcm record. */
export async function encryptPush(p256dh, auth, payload) {
  const ua = pushUnb64(p256dh);
  const secret = pushUnb64(auth);
  if (!ua || ua.length !== 65 || !secret || secret.length !== 16) throw new Error("push keys");
  const mine = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const as = new Uint8Array(await crypto.subtle.exportKey("raw", mine.publicKey));
  const theirs = await crypto.subtle.importKey("raw", ua, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: theirs }, mine.privateKey, 256));
  const prkKey = await pushHmac(secret, shared);
  const ikm = await pushHmac(prkKey, pushJoin(pushEnc.encode("WebPush: info\0"), ua, as, Uint8Array.of(1)));
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const prk = await pushHmac(salt, ikm);
  const cek = (await pushHmac(prk, pushJoin(pushEnc.encode("Content-Encoding: aes128gcm\0"), Uint8Array.of(1)))).slice(0, 16);
  const nonce = (await pushHmac(prk, pushJoin(pushEnc.encode("Content-Encoding: nonce\0"), Uint8Array.of(1)))).slice(0, 12);
  const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["encrypt"]);
  // One record, so the padding delimiter is 2 (the last record) with no padding after it.
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, key, pushJoin(pushEnc.encode(payload), Uint8Array.of(2))));
  const head = new Uint8Array(21);
  head.set(salt, 0);
  new DataView(head.buffer).setUint32(16, 4096);
  head[20] = as.length;
  return pushJoin(head, as, sealed);
}

/**
 * Sends [message] (an object, sent as JSON) to one subscription; the push service's status, or 0 when it
 * could not be reached. 404 and 410 mean the browser let go of it.
 */
export async function sendPush(keys, sub, message, now, urgency = "normal") {
  try {
    const body = await encryptPush(sub.p256dh, sub.auth, JSON.stringify(message));
    const res = await fetch(sub.endpoint, {
      method: "POST",
      headers: { authorization: await vapidAuth(keys, sub.endpoint, now), "content-encoding": "aes128gcm",
        "content-type": "application/octet-stream", ttl: "86400", urgency },
      body,
    });
    return res.status;
  } catch {
    return 0;
  }
}
