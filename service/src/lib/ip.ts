/**
 * Normalise a client IP into a rate-limit key. IPv6 collapses to its /64 prefix
 * (one subscriber typically controls a whole /64, so rotating addresses inside it
 * must not dodge limits). IPv4, "unknown" and unparseable input are returned unchanged.
 */
export function clientIpKey(ip: string): string {
  if (!ip.includes(":")) return ip;
  let addr = ip.split("%")[0].toLowerCase();
  // IPv4-mapped (::ffff:a.b.c.d): rate-limit as the IPv4 address.
  const mapped = addr.match(/^(?:0*:){2,5}ffff:(\d{1,3}(?:\.\d{1,3}){3})$/);
  if (mapped) return mapped[1];
  if (addr.includes(".")) return ip; // other embedded-IPv4 forms: leave alone
  const halves = addr.split("::");
  if (halves.length > 2) return ip;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  let groups: string[];
  if (halves.length === 2) {
    const missing = 8 - head.length - tail.length;
    if (missing < 1) return ip;
    groups = [...head, ...Array<string>(missing).fill("0"), ...tail];
  } else {
    groups = head;
  }
  if (groups.length !== 8 || !groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return ip;
  const prefix = groups.slice(0, 4).map((g) => parseInt(g, 16).toString(16));
  return `${prefix.join(":")}::/64`;
}
