import { describe, expect, it } from "vitest";
// Executes the browser script as a module (no DOM in workerd): it publishes its pure helpers.
import "../src/portal/portal.client.js?parity";

type Res = { ok: boolean; status: number; j: any };
const portal = () => (globalThis as any).OrbitTunerPortal as {
  pollDelay: (misses: number) => number;
  uniqueLinks: (text: string) => string[];
  pollJob: (id: string, deps: Deps) => void;
  POLL_MAX_MISSES: number;
};
type Deps = {
  get: (id: string) => Promise<Res>;
  schedule: (fn: () => void, ms: number) => void;
  hidden: () => boolean;
  onVisible: (fn: () => void) => void;
  render: (job: any) => void;
  stopped: (reason: "lost" | "session") => void;
};

/** Manual clock: scheduled ticks run only when the test says so. */
function harness(responses: (Res | Error)[], hidden = () => false) {
  const queue: { fn: () => void; ms: number }[] = [];
  const visible: (() => void)[] = [];
  const log = { gets: 0, delays: [] as number[], renders: [] as any[], stopped: [] as string[] };
  const deps: Deps = {
    get: async () => {
      log.gets++;
      const r = responses.shift() ?? new Error("no more");
      if (r instanceof Error) throw r;
      return r;
    },
    schedule: (fn, ms) => { log.delays.push(ms); queue.push({ fn, ms }); },
    hidden,
    onVisible: (fn) => { visible.push(fn); },
    render: (job) => { log.renders.push(job); },
    stopped: (reason) => { log.stopped.push(reason); },
  };
  const step = async () => {
    const next = queue.shift();
    if (!next) return false;
    next.fn();
    for (let i = 0; i < 5; i++) await Promise.resolve();
    return true;
  };
  return { deps, log, step, visible, queue };
}

const job = (status: string, done = 0) => ({ ok: true, status: 200, j: { id: "J", status, total: 2, done, items: [] } });
const fail = (status = 500): Res => ({ ok: false, status, j: { error: "internal" } });

describe("portal client: link-import polling", () => {
  it("backs off 2, 4, 8, 16 s on errors, capped at 30 s", () => {
    const { pollDelay } = portal();
    expect([0, 1, 2, 3, 4, 5, 6, 9].map(pollDelay)).toEqual([2000, 2000, 4000, 8000, 16000, 30000, 30000, 30000]);
  });

  it("polls every 2 s while running, stops quietly when done", async () => {
    const h = harness([job("running"), job("running", 1), job("done", 2)]);
    portal().pollJob("J", h.deps);
    while (await h.step());
    expect(h.log.gets).toBe(3);
    expect(h.log.delays).toEqual([2000, 2000, 2000]);
    expect(h.log.renders.map((j) => j.status)).toEqual(["running", "running", "done"]);
    expect(h.log.stopped).toEqual([]);
  });

  it("backs off on errors, resets after a success, and reports 'lost' after too many misses", async () => {
    const { POLL_MAX_MISSES } = portal();
    const h = harness([fail(), new Error("net"), job("running"), ...Array.from({ length: POLL_MAX_MISSES }, () => fail(503))]);
    portal().pollJob("J", h.deps);
    while (await h.step());
    expect(h.log.delays.slice(0, 4)).toEqual([2000, 2000, 4000, 2000]);
    expect(h.log.delays.slice(4)).toEqual([2000, 4000, 8000, 16000, 30000].slice(0, POLL_MAX_MISSES - 1));
    expect(h.log.stopped).toEqual(["lost"]);
  });

  it("401 stops with 'session'; 404 stops with 'lost'", async () => {
    let h = harness([fail(401)]);
    portal().pollJob("J", h.deps);
    while (await h.step());
    expect(h.log.stopped).toEqual(["session"]);
    h = harness([fail(404)]);
    portal().pollJob("J", h.deps);
    while (await h.step());
    expect(h.log.stopped).toEqual(["lost"]);
  });

  it("pauses while the page is hidden and resumes when visible", async () => {
    let hidden = true;
    const h = harness([job("done", 2)], () => hidden);
    portal().pollJob("J", h.deps);
    await h.step();
    expect(h.log.gets).toBe(0);
    expect(h.visible.length).toBe(1);
    hidden = false;
    h.visible[0]();
    for (let i = 0; i < 5; i++) await Promise.resolve();
    expect(h.log.gets).toBe(1);
    expect(h.log.renders.length).toBe(1);
  });

  it("a newer poller stops the older one", async () => {
    const a = harness([job("running"), job("running")]);
    const b = harness([job("running"), job("done")]);
    portal().pollJob("A", a.deps);
    portal().pollJob("B", b.deps);
    await a.step(); // A's first tick sees it is stale: no request
    expect(a.log.gets).toBe(0);
    expect(a.queue.length).toBe(0);
    while (await b.step());
    expect(b.log.gets).toBe(2);
  });
});

describe("portal client: link counting", () => {
  it("counts unique trimmed non-empty lines", () => {
    const { uniqueLinks } = portal();
    expect(uniqueLinks(" https://a.example/1 \n\nhttps://a.example/1\r\nhttps://b.example/2\n   \n")).toEqual([
      "https://a.example/1",
      "https://b.example/2",
    ]);
    const many = Array.from({ length: 250 }, (_, i) => `https://x.example/${i % 201}`).join("\n");
    expect(uniqueLinks(many).length).toBe(201);
  });
});
