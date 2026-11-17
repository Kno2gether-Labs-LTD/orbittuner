import { describe, expect, it } from "vitest";
import { ulid } from "../src/lib/ulid";

describe("ulid", () => {
  it("matches Crockford base32, 26 chars", () => {
    expect(ulid()).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  });
  it("sorts ascending within the same millisecond", () => {
    const ids = Array.from({ length: 50 }, () => ulid());
    const sorted = [...ids].sort();
    expect(ids).toEqual(sorted);
    expect(new Set(ids).size).toBe(50);
  });
});
