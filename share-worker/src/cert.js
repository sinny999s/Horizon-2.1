/**
 * Proof that a request comes from a Minecraft account's owner, checked without Mojang's servers (they answer 403 to
 * Cloudflare Workers, hasJoined included). The game holds a chat-signing key pair for the account; Mojang signs its
 * public key (the "player certificate"). The client sends that certificate and signs a fresh challenge with the private
 * key; this checks Mojang's signature over the certificate (as vanilla's ProfilePublicKey.Data.validateSignature
 * does: SHA1withRSA over uuid, expiry and key) and the challenge's (SHA256withRSA, as the game signs chat).
 */

/** Mojang's player certificate keys (playerCertificateKeys of https://api.minecraftservices.com/publickeys). */
export const MOJANG_CERT_KEYS = [
  "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNBtb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3lnCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYcYOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPPJL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePvtcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCxFbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==",
  "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAt4t9NPuu7cktclnaH7eZj0omkLcJHeLz5MKsyJEntHZ0INtuBjSSul3Pp3pBeJN8k3ADdcdBLUN90bcAi7WsQqTx3Ft363q3W7TbM8j2iTEdp/0uVspoRt/DP1tkaWFs/w2WwUv9jbVoBUzfUc4pSTIxRwdjmqjZQfvjwKNDbOx3IhP2H0WXodbISejPi1wBZqNW4m1rnZAXp/EpUguxA8mobCa4vUCBkyFDyXdl69/wUSJHyCPmgcMJ364OlAhIqtwVPShBZObvrK/f0BYk6ShJD3N7TFDatSYsIIdcTKRknaIm91s+EsMrdB9U4Yw+ZJ/pyCB4S3vk8zfDCnb0DWIxYH3/EMzaxl77djmTmMzi/JDITup5z3jfWtRZmrAhU2/+W5IO5hEpo3/bCS9PXIY5xb41Lmp2ZO8dXKtyD66Chchy0W129n8vPl2GIruOdrxsjZAHnneyAb9jm0uaGaphwnEnuecX/qgHY6ZMtayvLLsPst8PO6R1vufMy8WqjK+j7LnC1krL7CPDg0NEhyQTmw5l+NCNjSlvB1juM9V4PARg0bYCOkGXm7ydRCjSSH8CJXZpwnd5cBB5WKAX3KPzutRgMi/LFwNSMZzFuUyXaYOZPpD259yqph1LmGqegEdDriACVU+dVEONFMm8eIuBofe7ljmsAFKW9BINwK0CAwEAAQ==",
];

/** How far the challenge's time may be from now, in ms. */
export const SKEW = 5 * 60 * 1000;

const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

/** The text the client signs: binds the signature to this service, the account and the time. */
export const challenge = (uuid, time) => `horizon-share auth ${uuid} ${time}`;

/** Vanilla's signedPayload: uuid (most, least bits), expiresAt (epoch ms), the key's X.509 bytes; big-endian. */
export function signedPayload(uuid, expiresAt, keyDer) {
  const out = new Uint8Array(24 + keyDer.length);
  const view = new DataView(out.buffer);
  view.setBigUint64(0, BigInt("0x" + uuid.slice(0, 16)));
  view.setBigUint64(8, BigInt("0x" + uuid.slice(16)));
  view.setBigInt64(16, BigInt(expiresAt));
  out.set(keyDer, 24);
  return out;
}

const rsa = (der, hash) => crypto.subtle.importKey("spki", der, { name: "RSASSA-PKCS1-v1_5", hash }, false, ["verify"]);
const verify = (key, sig, data) => crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, sig, data);

/**
 * The account (32 hex digits) [body] proves, or an error text. [body]: {uuid, expiresAt, key, keySignature, time,
 * signature}, the byte fields base64. [mojangKeys] (base64 X.509) and [nowMs] can be swapped in for tests.
 */
export async function provenAccount(body, mojangKeys = MOJANG_CERT_KEYS, nowMs = Date.now()) {
  const uuid = typeof body.uuid === "string" ? body.uuid.toLowerCase().replace(/-/g, "") : "";
  if (!/^[0-9a-f]{32}$/.test(uuid)) return { error: "bad uuid" };
  const { expiresAt, time } = body;
  if (!Number.isSafeInteger(expiresAt) || !Number.isSafeInteger(time)) return { error: "bad times" };
  if (expiresAt < nowMs) return { error: "the certificate has expired" };
  if (Math.abs(time - nowMs) > SKEW) return { error: "the challenge's time is off" };
  let keyDer, keySig, sig;
  try {
    keyDer = b64(body.key);
    keySig = b64(body.keySignature);
    sig = b64(body.signature);
  } catch (e) {
    return { error: "bad base64" };
  }
  const payload = signedPayload(uuid, expiresAt, keyDer);
  let mojang = false;
  for (const k of mojangKeys) {
    if (await verify(await rsa(b64(k), "SHA-1"), keySig, payload)) {
      mojang = true;
      break;
    }
  }
  if (!mojang) return { error: "Mojang didn't sign that key" };
  let player;
  try {
    player = await rsa(keyDer, "SHA-256");
  } catch (e) {
    return { error: "bad key" };
  }
  if (!(await verify(player, sig, new TextEncoder().encode(challenge(uuid, time))))) return { error: "bad signature" };
  return { uuid };
}
