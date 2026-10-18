/*CORE-START*/
function utf8(s) { return new TextEncoder().encode(s); }
function toHex(bytes) {
  var s = "";
  for (var i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? "0" : "") + bytes[i].toString(16);
  return s;
}
function randomBytes(n) {
  var b = new Uint8Array(n);
  (typeof crypto !== "undefined" ? crypto : require("crypto").webcrypto).getRandomValues(b);
  return b;
}
function subtle() { return (typeof crypto !== "undefined" ? crypto : require("crypto").webcrypto).subtle; }
function b64std(bytes) { var s = ""; for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]); return btoa(s); }
function b64url(bytes) { return b64std(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
function makeSerial() { return toHex(randomBytes(4)).toUpperCase(); }

// The phone shows its Device ID as XXXXX-XXXXX. These are the only letters/digits it uses.
var ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
function normalizeDeviceId(raw) {
  var s = String(raw || "").toUpperCase().replace(/[\s-]/g, "");
  if (s.length !== 10) throw new Error("Device ID must be 10 letters/digits (like 7K2F9-QXA3B)");
  for (var i = 0; i < s.length; i++)
    if (ID_ALPHABET.indexOf(s[i]) < 0) throw new Error("Device ID has a character the app never uses: " + s[i] + " (no I, O, 0 or 1)");
  return s;
}

// ---- signing: the admin keeps the PRIVATE key (inside the locked vault). The app only holds the PUBLIC key,
// so even someone who takes the app apart cannot make a valid token.
function generateSigningKey() {
  return subtle().generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]).then(function (kp) {
    return Promise.all([subtle().exportKey("jwk", kp.privateKey), subtle().exportKey("spki", kp.publicKey)]);
  }).then(function (r) { return { privJwk: r[0], pub: b64std(new Uint8Array(r[1])) }; });
}
// Token = BN$V$<serial>$<credits>$<deviceId>$<signature>. No * characters, so WhatsApp leaves it alone.
function makeVoucher(serial, credits, deviceId, privJwk) {
  credits = Math.floor(Number(credits));
  if (!(credits > 0)) return Promise.reject(new Error("credits must be more than 0"));
  if (!privJwk) return Promise.reject(new Error("make the app key first (tab 1)"));
  var id;
  try { id = normalizeDeviceId(deviceId); } catch (e) { return Promise.reject(e); }
  var body = "BN$V$" + serial + "$" + credits + "$" + id;
  var jwk = Object.assign({}, privJwk, { key_ops: ["sign"] });
  return subtle().importKey("jwk", jwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]).then(function (k) {
    return subtle().sign({ name: "ECDSA", hash: "SHA-256" }, k, utf8(body));
  }).then(function (sig) { return body + "$" + b64url(new Uint8Array(sig)); });
}
/*CORE-END*/
if (typeof module !== "undefined") module.exports = { makeSerial: makeSerial, normalizeDeviceId: normalizeDeviceId, generateSigningKey: generateSigningKey, makeVoucher: makeVoucher };
