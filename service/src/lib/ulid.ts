const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

let lastTime = -1;
let lastRandom: number[] = [];

function randomDigits(): number[] {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b & 31);
}

/** Monotonic ULID: Crockford base32, 26 chars, 48-bit ms time + 80-bit random. */
export function ulid(now: number = Date.now()): string {
  let time = now;
  if (time <= lastTime) {
    // Same (or earlier) millisecond: increment the previous random part.
    time = lastTime;
    const r = lastRandom.slice();
    let i = r.length - 1;
    while (i >= 0) {
      if (r[i] === 31) {
        r[i] = 0;
        i--;
      } else {
        r[i]++;
        break;
      }
    }
    if (i < 0) {
      // 80-bit overflow (practically impossible): advance the clock.
      time = lastTime + 1;
      lastRandom = randomDigits();
    } else {
      lastRandom = r;
    }
  } else {
    lastRandom = randomDigits();
  }
  lastTime = time;

  let t = time;
  let out = "";
  for (let i = 0; i < 10; i++) {
    out = ALPHABET[t % 32] + out;
    t = Math.floor(t / 32);
  }
  return out + lastRandom.map((d) => ALPHABET[d]).join("");
}
