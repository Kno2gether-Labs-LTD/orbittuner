import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { ApiError } from "../src/lib/errors";
import { deleteSecret, getSecret, secretStatus, setSecret } from "../src/lib/secrets";
import type { Env } from "../src/env";

const e = env as unknown as Env;

beforeEach(async () => {
  await e.DB.prepare("DELETE FROM secrets").run();
});

async function expectApi(p: Promise<unknown>, status: number, code: string) {
  const err = await p.then(() => null, (x) => x);
  expect(err).toBeInstanceOf(ApiError);
  expect((err as ApiError).status).toBe(status);
  expect((err as ApiError).code).toBe(code);
}

describe("secrets store", () => {
  it("round-trips", async () => {
    await setSecret(e, "ANTHROPIC_API_KEY", "sk-ant-roundtrip-abcd");
    expect(await getSecret(e, "ANTHROPIC_API_KEY")).toBe("sk-ant-roundtrip-abcd");
    const row = await e.DB.prepare("SELECT ciphertext FROM secrets WHERE name='ANTHROPIC_API_KEY'").first<{ ciphertext: string }>();
    expect(row!.ciphertext).not.toContain("roundtrip");
  });

  it("D1 value overrides env", async () => {
    await setSecret(e, "INWORLD_API_KEY", "portal-inworld-key-wxyz");
    expect(await getSecret(e, "INWORLD_API_KEY")).toBe("portal-inworld-key-wxyz");
  });

  it("falls back to env when D1 has none", async () => {
    expect(await getSecret(e, "OPENAI_API_KEY")).toBe("test-openai-key-5678");
    expect(await getSecret(e, "CUSTOM_AI_KEY_1")).toBeUndefined();
  });

  it("secretStatus shows last4 and never the value", async () => {
    await setSecret(e, "ANTHROPIC_API_KEY", "sk-ant-secretvalue-4321");
    const st = await secretStatus(e);
    const json = JSON.stringify(st);
    expect(json).not.toContain("secretvalue");
    expect(json).not.toContain("test-openai-key");
    expect(st.find((s) => s.name === "ANTHROPIC_API_KEY")).toEqual({ name: "ANTHROPIC_API_KEY", source: "portal", last4: "4321" });
    expect(st.find((s) => s.name === "OPENAI_API_KEY")?.source).toBe("env");
    expect(st.find((s) => s.name === "CUSTOM_AI_KEY_2")?.source).toBe("missing");
  });

  it("ciphertext swapped between names fails (AAD) and falls back to env", async () => {
    await setSecret(e, "INWORLD_API_KEY", "portal-inworld-key-wxyz");
    const row = await e.DB.prepare("SELECT ciphertext, iv FROM secrets WHERE name='INWORLD_API_KEY'").first<{ ciphertext: string; iv: string }>();
    await e.DB.prepare("INSERT INTO secrets (name, ciphertext, iv, last4, updated_at) VALUES ('OPENAI_API_KEY', ?, ?, 'wxyz', 1)")
      .bind(row!.ciphertext, row!.iv).run();
    expect(await getSecret(e, "OPENAI_API_KEY")).toBe("test-openai-key-5678");
  });

  it("delete removes portal value", async () => {
    await setSecret(e, "INWORLD_API_KEY", "portal-inworld-key-wxyz");
    await deleteSecret(e, "INWORLD_API_KEY");
    expect(await getSecret(e, "INWORLD_API_KEY")).toBe("test-inworld-key-1234");
  });

  it("missing SECRETS_KEY -> 503 secrets_disabled; reads use env", async () => {
    const noKey = { ...e, SECRETS_KEY: undefined } as Env;
    await expectApi(setSecret(noKey, "OPENAI_API_KEY", "some-long-value"), 503, "secrets_disabled");
    expect(await getSecret(noKey, "OPENAI_API_KEY")).toBe("test-openai-key-5678");
  });

  it("unknown name / bad value length -> 400", async () => {
    await expectApi(setSecret(e, "NOPE", "some-long-value"), 400, "invalid_name");
    await expectApi(setSecret(e, "OPENAI_API_KEY", "short"), 400, "invalid_value");
    await expectApi(setSecret(e, "OPENAI_API_KEY", "x".repeat(4097)), 400, "invalid_value");
  });

  it("trims the value before storing", async () => {
    await setSecret(e, "OPENAI_API_KEY", "  \tportal-openai-key-abcd\n ");
    expect(await getSecret(e, "OPENAI_API_KEY")).toBe("portal-openai-key-abcd");
    expect((await secretStatus(e)).find((s) => s.name === "OPENAI_API_KEY")!.last4).toBe("abcd");
    await expectApi(setSecret(e, "OPENAI_API_KEY", "   short    "), 400, "invalid_value");
  });

  it("flags a portal row unreadable when SECRETS_KEY is missing or invalid", async () => {
    await setSecret(e, "ANTHROPIC_API_KEY", "sk-ant-secretvalue-4321");
    for (const bad of [undefined, "not-hex"]) {
      const st = await secretStatus({ ...e, SECRETS_KEY: bad } as Env);
      expect(st.find((s) => s.name === "ANTHROPIC_API_KEY")).toEqual({ name: "ANTHROPIC_API_KEY", source: "portal", last4: "4321", unreadable: true });
    }
    expect((await secretStatus(e)).find((s) => s.name === "ANTHROPIC_API_KEY")!.unreadable).toBeUndefined();
  });

  it("omits last4 for env values shorter than 8 characters", async () => {
    const st = await secretStatus({ ...e, CUSTOM_AI_KEY_1: "abc1234" } as Env);
    expect(st.find((s) => s.name === "CUSTOM_AI_KEY_1")).toEqual({ name: "CUSTOM_AI_KEY_1", source: "env" });
  });

  it("works across repeated calls with the cached key", async () => {
    await setSecret(e, "OPENAI_API_KEY", "portal-openai-key-one1");
    await setSecret(e, "INWORLD_API_KEY", "portal-inworld-key-two2");
    expect(await getSecret(e, "OPENAI_API_KEY")).toBe("portal-openai-key-one1");
    expect(await getSecret(e, "INWORLD_API_KEY")).toBe("portal-inworld-key-two2");
    // A different (valid) key cannot read rows written under the first one: env is used.
    expect(await getSecret({ ...e, SECRETS_KEY: "cd".repeat(32) } as Env, "OPENAI_API_KEY")).toBe("test-openai-key-5678");
  });
});
