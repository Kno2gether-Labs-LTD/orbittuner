import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { assertPublicUrl, fetchPage } from "../src/ai/fetchPage";
import { htmlToText } from "../src/ai/html";
import { ApiError } from "../src/lib/errors";

const FAKE_TOKEN = "apify_api_FAKE_TEST_TOKEN_0000";
const withToken = { getToken: async () => FAKE_TOKEN };
const noToken = { getToken: async () => undefined };

const LONG_SHEET = `<html><head><title>Song</title><script>var x = "evil";</script></head><body>
<nav>Home | Songs | Login</nav>
<h1>Amazing Grace</h1>
<pre>
G          C        G
Amazing grace how sweet the sound
G                     D
That saved a wretch like me
G          C        G
I once was lost but now am found
G       D       G
Was blind but now I see
</pre>
<footer>Copyright footer</footer>
</body></html>`;

function htmlResponse(body: string | Uint8Array, status = 200, headers: Record<string, string> = {}) {
  return new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8", ...headers } });
}

function redirect(location: string, status = 302) {
  return new Response(null, { status, headers: { location } });
}

function expectApiError(e: unknown, status: number, code: string) {
  expect(e).toBeInstanceOf(ApiError);
  expect((e as ApiError).status).toBe(status);
  expect((e as ApiError).code).toBe(code);
}

async function catchErr(p: Promise<unknown>): Promise<unknown> {
  try {
    await p;
  } catch (e) {
    return e;
  }
  throw new Error("expected rejection");
}

function catchSync(f: () => unknown): unknown {
  try {
    f();
  } catch (e) {
    return e;
  }
  throw new Error("expected throw");
}

type Call = { url: string; init?: RequestInit };
let calls: Call[];

function mockFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  calls = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    calls.push({ url, init });
    return handler(url, init);
  });
}

beforeEach(async () => {
  await env.DB.prepare("DELETE FROM settings WHERE key = 'apify_actor'").run();
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe("assertPublicUrl", () => {
  const bad = [
    "ftp://example.com/song",
    "file:///etc/passwd",
    "javascript:alert(1)",
    "not a url",
    "http://user:pass@example.com/",
    "http://user@example.com/",
    "http://localhost:8787/",
    "http://localhost/",
    "http://api.localhost/",
    "http://printer.local/",
    "http://metadata.google.internal/",
    "http://LOCALHOST./",
    "http://localhost../",
    "http://foo.internal../",
    "http://127.1/",
    "http://192.88.99.1/",
    "http://[100::1]/",
    "http://[2001:10::1]/",
    "http://[2001:20::1]/",
    "http://intranet/",
    "http://example.com:8080/",
    "https://example.com:22/",
    "http://127.0.0.1/",
    "http://127.1.2.3/",
    "http://10.0.0.1/",
    "http://172.16.5.4/",
    "http://172.31.255.255/",
    "http://192.168.1.1/",
    "http://169.254.169.254/latest/meta-data/",
    "http://100.64.0.1/",
    "http://100.127.255.254/",
    "http://0.0.0.0/",
    "http://255.255.255.255/",
    "http://224.0.0.1/",
    "http://2130706433/",
    "http://0x7f.1/",
    "http://0177.0.0.1/",
    "http://0x7f000001/",
    "http://[::1]/",
    "http://[::]/",
    "http://[::ffff:127.0.0.1]/",
    "http://[::ffff:7f00:1]/",
    "http://[::ffff:169.254.169.254]/",
    "http://[fe80::1]/",
    "http://[fc00::1]/",
    "http://[fd12:3456::1]/",
    "http://[ff02::1]/",
    "http://[64:ff9b::a9fe:a9fe]/",
    "http://[2002:7f00:1::]/",
  ];
  for (const u of bad) {
    it(`rejects ${u}`, () => {
      expectApiError(catchSync(() => assertPublicUrl(u)), 400, "bad_url");
    });
  }

  it("rejects a full-width-digit IDNA host that maps to 127.0.0.1", () => {
    expectApiError(catchSync(() => assertPublicUrl("http://\uff11\uff12\uff17.\uff10.\uff10.\uff11/")), 400, "bad_url");
  });

  const good = [
    "https://www.ultimate-guitar.com/tab/x",
    "http://example.com/song",
    "http://example.com:80/",
    "https://example.com:443/",
    "http://example.com:443/",
    "http://8.8.8.8/",
    "http://172.32.0.1/",
    "http://[2606:4700:4700::1111]/",
  ];
  for (const u of good) {
    it(`accepts ${u}`, () => {
      expect(assertPublicUrl(u)).toBeInstanceOf(URL);
    });
  }
});

describe("htmlToText", () => {
  it("keeps <pre> alignment verbatim and drops script/style/nav/footer/header", () => {
    const html = `<html><head><style>.a{color:red}</style><script>alert("x")</script></head><body>
<header>Site header</header><nav><a href="/">Home</a></nav>
<h1>Title &amp; More</h1><p>Line one<br>Line two</p>
<pre>  G       C
Hello &lt;world&gt;   there
    <b>Am</b>    F</pre>
<footer>foot</footer></body></html>`;
    const text = htmlToText(html);
    expect(text).toContain("  G       C\nHello <world>   there\n    Am    F");
    expect(text).toContain("Title & More");
    expect(text).toContain("Line one\nLine two");
    expect(text).not.toContain("alert");
    expect(text).not.toContain("color:red");
    expect(text).not.toContain("Site header");
    expect(text).not.toContain("Home");
    expect(text).not.toContain("foot");
  });

  it("is linear on unclosed tags (2 MB adversarial input)", () => {
    for (const html of ["<nav>".repeat(400_000), "<pre>".repeat(400_000), "<script>".repeat(400_000), "<a".repeat(1_000_000), "<!--".repeat(500_000)]) {
      const t0 = Date.now();
      htmlToText(html);
      expect(Date.now() - t0).toBeLessThan(200);
    }
  });

  it("ignores <pre> inside script strings and keeps a literal < inside <pre>", () => {
    const html = `<script>var s = "<pre>evil</pre>";</script><p>Intro</p><pre>C < D   x &lt; y
  Am     G</pre><p>after</p>`;
    const text = htmlToText(html);
    expect(text).not.toContain("evil");
    expect(text).toContain("C < D   x < y\n  Am     G");
    expect(text).toContain("after");
  });

  it("matches closing tags on a name boundary: </header> does not close an unclosed <head>", () => {
    const text = htmlToText("<head><title>T</title><p>Intro kept</p><header>Site</header><p>Body kept</p>");
    expect(text).toContain("Intro kept");
    expect(text).toContain("Body kept");
    expect(text).not.toContain("Site");
  });

  it("treats a self-closing <svg/> as void", () => {
    const text = htmlToText('<p>Before</p><svg viewBox="0 0 1 1"/><p>After the icon</p><svg><path/></svg><p>End</p>');
    expect(text).toContain("After the icon");
    expect(text).toContain("End");
  });

  it("strips comments inside <pre>", () => {
    expect(htmlToText("<pre>G   C<!-- ad slot -->\nla la</pre>")).toBe("G   C\nla la");
  });

  it("drops an unclosed nav open tag but keeps the text after it", () => {
    expect(htmlToText("<nav>Hello world")).toContain("Hello world");
  });

  it("decodes numeric and named entities and collapses blank lines", () => {
    const text = htmlToText("<p>a&#39;b&#x2019;c&nbsp;d&sharp;</p>\n\n\n\n\n\n<p>e</p>");
    expect(text).toContain("a'b’c d♯");
    expect(text).not.toMatch(/\n{4,}/);
  });
});

describe("fetchPage direct", () => {
  it("fetches a public page with a browser UA and manual redirects", async () => {
    mockFetch(() => htmlResponse(LONG_SHEET));
    const r = await fetchPage(env, "https://chords.example.com/amazing", noToken);
    expect(r.via).toBe("direct");
    expect(r.finalUrl).toBe("https://chords.example.com/amazing");
    expect(r.text).toContain("Amazing grace how sweet the sound");
    expect(r.text).toContain("G          C        G");
    expect(calls).toHaveLength(1);
    const headers = new Headers(calls[0].init?.headers);
    expect(headers.get("user-agent")).toMatch(/Mozilla\/5\.0/);
    expect(headers.get("accept")).toContain("text/html");
    expect(calls[0].init?.redirect).toBe("manual");
  });

  it("rejects a bad URL before any fetch", async () => {
    mockFetch(() => htmlResponse(LONG_SHEET));
    expectApiError(await catchErr(fetchPage(env, "http://localhost:8787/x", withToken)), 400, "bad_url");
    expect(calls).toHaveLength(0);
  });

  it("follows up to 3 redirects, re-checking each", async () => {
    mockFetch((url) => {
      if (url === "https://a.example.com/") return redirect("https://b.example.com/");
      if (url === "https://b.example.com/") return redirect("/c", 301);
      if (url === "https://b.example.com/c") return redirect("https://d.example.com/final", 307);
      return htmlResponse(LONG_SHEET);
    });
    const r = await fetchPage(env, "https://a.example.com/", noToken);
    expect(r.finalUrl).toBe("https://d.example.com/final");
    expect(calls).toHaveLength(4);
  });

  it("blocks a redirect to the cloud metadata IP and never fetches it or calls Apify", async () => {
    mockFetch((url) => {
      if (url === "https://evil.example.com/") return redirect("http://169.254.169.254/latest/meta-data/");
      return htmlResponse(LONG_SHEET);
    });
    expectApiError(await catchErr(fetchPage(env, "https://evil.example.com/", withToken)), 400, "bad_url");
    expect(calls.map((c) => c.url)).toEqual(["https://evil.example.com/"]);
  });

  it("blocks a redirect to localhost:8787", async () => {
    mockFetch(() => redirect("http://localhost:8787/admin"));
    expectApiError(await catchErr(fetchPage(env, "https://evil.example.com/", withToken)), 400, "bad_url");
    expect(calls).toHaveLength(1);
  });

  it("blocks more than 3 redirects", async () => {
    let n = 0;
    mockFetch(() => redirect(`https://hop${++n}.example.com/`));
    expectApiError(await catchErr(fetchPage(env, "https://start.example.com/", withToken)), 502, "fetch_failed");
    expect(calls).toHaveLength(4);
    expect(calls.every((c) => !c.url.includes("api.apify.com"))).toBe(true);
  });

  it("cuts a body over 2 MB", async () => {
    const big = "<pre>" + "G C D Em\n".repeat(400_000) + "</pre>"; // ~3.6 MB
    let cancelled = false;
    mockFetch(() => {
      const bytes = new TextEncoder().encode(big);
      let off = 0;
      const stream = new ReadableStream<Uint8Array>({
        pull(ctrl) {
          if (off >= bytes.length) return ctrl.close();
          ctrl.enqueue(bytes.slice(off, off + 64 * 1024));
          off += 64 * 1024;
        },
        cancel() {
          cancelled = true;
        },
      });
      return new Response(stream, { headers: { "content-type": "text/html" } });
    });
    const r = await fetchPage(env, "https://big.example.com/", noToken);
    expect(r.via).toBe("direct");
    expect(r.text.length).toBeGreaterThan(1_000_000);
    expect(r.text.length).toBeLessThanOrEqual(2 * 1024 * 1024);
    expect(cancelled).toBe(true);
  });

  it("rejects a non-HTML content type", async () => {
    mockFetch(() => new Response("%PDF-1.4", { headers: { "content-type": "application/pdf" } }));
    expectApiError(await catchErr(fetchPage(env, "https://x.example.com/a.pdf", withToken)), 415, "unsupported_type");
    expect(calls).toHaveLength(1);
  });

  it("accepts text/plain", async () => {
    const body = "G C D\n".repeat(100);
    mockFetch(() => new Response(body, { headers: { "content-type": "text/plain" } }));
    const r = await fetchPage(env, "https://x.example.com/a.txt", noToken);
    expect(r.text).toContain("G C D");
  });
});

describe("fetchPage Apify fallback", () => {
  function apifyMock(direct: () => Response, apifyItems: unknown = [{ markdown: "# Amazing Grace\n\n[G]Amazing [C]grace" }]) {
    mockFetch((url) => {
      if (url.startsWith("https://api.apify.com/")) return Response.json(apifyItems, { status: 201 });
      return direct();
    });
  }

  it("403 -> Apify called with the correct body and the result used", async () => {
    apifyMock(() => htmlResponse("Forbidden", 403));
    const r = await fetchPage(env, "https://blocked.example.com/song", withToken);
    expect(r.via).toBe("apify");
    expect(r.text).toBe("# Amazing Grace\n\n[G]Amazing [C]grace");
    expect(r.finalUrl).toBe("https://blocked.example.com/song");
    const call = calls.find((c) => c.url.startsWith("https://api.apify.com/"))!;
    expect(call.url).toBe("https://api.apify.com/v2/acts/apify~rag-web-browser/run-sync-get-dataset-items?timeout=60");
    expect(call.init?.method).toBe("POST");
    const h = new Headers(call.init?.headers);
    expect(h.get("authorization")).toBe(`Bearer ${FAKE_TOKEN}`);
    expect(h.get("content-type")).toBe("application/json");
    expect(JSON.parse(call.init?.body as string)).toEqual({
      query: "https://blocked.example.com/song",
      maxResults: 1,
      outputFormats: ["markdown"],
    });
  });

  for (const status of [429, 503]) {
    it(`${status} -> Apify`, async () => {
      apifyMock(() => htmlResponse("busy", status));
      expect((await fetchPage(env, "https://x.example.com/", withToken)).via).toBe("apify");
    });
  }

  it("uses the apify_actor setting", async () => {
    await env.DB.prepare("INSERT INTO settings (key, value) VALUES ('apify_actor', 'someone~my-scraper')").run();
    apifyMock(() => htmlResponse("no", 403));
    await fetchPage(env, "https://x.example.com/", withToken);
    expect(calls.some((c) => c.url.startsWith("https://api.apify.com/v2/acts/someone~my-scraper/run-sync"))).toBe(true);
  });

  it("ignores a malformed apify_actor setting", async () => {
    await env.DB.prepare("INSERT INTO settings (key, value) VALUES ('apify_actor', '../../users/me?x=')").run();
    apifyMock(() => htmlResponse("no", 403));
    await fetchPage(env, "https://x.example.com/", withToken);
    expect(calls.some((c) => c.url.startsWith("https://api.apify.com/v2/acts/apify~rag-web-browser/"))).toBe(true);
  });

  it("a captcha page -> Apify", async () => {
    const captcha = `<html><head><title>Just a moment...</title></head><body>${"<p>Checking your browser before accessing the site, please wait while we verify you are human.</p>".repeat(5)}<div id="cf-chl-widget"></div></body></html>`;
    apifyMock(() => htmlResponse(captcha, 200));
    const r = await fetchPage(env, "https://cf.example.com/", withToken);
    expect(r.via).toBe("apify");
  });

  it("a good <pre> sheet with a reCAPTCHA script stays direct", async () => {
    const page = LONG_SHEET.replace("</body>", '<script src="https://www.google.com/recaptcha/api.js"></script><div class="g-recaptcha">captcha</div></body>');
    apifyMock(() => htmlResponse(page));
    expect((await fetchPage(env, "https://ok.example.com/", withToken)).via).toBe("direct");
  });

  it("a lyric containing 'just a moment' stays direct", async () => {
    const page = `<html><head><title>Wait - Lyrics</title></head><body><p>${"Just a moment, wait for me, just a moment more my love. ".repeat(10)}</p></body></html>`;
    apifyMock(() => htmlResponse(page));
    expect((await fetchPage(env, "https://ok.example.com/", withToken)).via).toBe("direct");
  });

  it("a Cloudflare challenge page with plenty of text -> Apify", async () => {
    const page = `<html><head><title>Attention Required! | Cloudflare</title></head><body>${"<p>Please enable cookies. This website is using a security service to protect itself from online attacks.</p>".repeat(30)}</body></html>`;
    apifyMock(() => htmlResponse(page));
    expect((await fetchPage(env, "https://cf.example.com/", withToken)).via).toBe("apify");
  });

  it("a good sheet with Cloudflare JS Detections (jsd/main.js) stays direct", async () => {
    const page = LONG_SHEET.replace("</body>", '<script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script></body>');
    apifyMock(() => htmlResponse(page));
    expect((await fetchPage(env, "https://ok.example.com/", withToken)).via).toBe("direct");
  });

  it("a long prose page with jsd/main.js stays direct", async () => {
    const page = `<html><body>${"<p>Verse text that is long enough to count as content for the converter.</p>".repeat(10)}<script>window.__cf$cv$params={};</script><script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script></body></html>`;
    apifyMock(() => htmlResponse(page));
    expect((await fetchPage(env, "https://ok.example.com/", withToken)).via).toBe("direct");
  });

  it("a short challenge-platform/h/ page -> Apify", async () => {
    const page = `<html><head><title>One more step</title></head><body><p>${"Checking the connection, please enable JavaScript and cookies to continue. ".repeat(4)}</p><script>window._cf_chl_opt={cvId:'3'};</script><script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script></body></html>`;
    apifyMock(() => htmlResponse(page));
    expect((await fetchPage(env, "https://cf.example.com/", withToken)).via).toBe("apify");
  });

  it("a short page asking to verify you are human -> Apify", async () => {
    const page = `<html><body><p>${"Please verify you are human by completing the action below. ".repeat(6)}</p></body></html>`;
    apifyMock(() => htmlResponse(page));
    expect((await fetchPage(env, "https://x.example.com/", withToken)).via).toBe("apify");
  });

  it("weak marker but usable direct text, Apify unavailable -> direct result", async () => {
    const page = `<html><body><p>${"Please verify you are human by completing the action below. ".repeat(6)}</p></body></html>`;
    apifyMock(() => htmlResponse(page));
    const r = await fetchPage(env, "https://x.example.com/", noToken);
    expect(r.via).toBe("direct");
    expect(r.text).toContain("verify you are human");
  });

  it("weak marker but usable direct text, Apify erroring -> direct result", async () => {
    const page = `<html><body><p>${"Please verify you are human by completing the action below. ".repeat(6)}</p></body></html>`;
    mockFetch((url) => (url.startsWith("https://api.apify.com/") ? new Response("down", { status: 500 }) : htmlResponse(page)));
    expect((await fetchPage(env, "https://x.example.com/", withToken)).via).toBe("direct");
  });

  it("a challenge title page with Apify unavailable -> 502 (challenge text is never returned)", async () => {
    const page = `<html><head><title>Just a moment...</title></head><body>${"<p>Checking your browser before accessing the site, please wait.</p>".repeat(10)}</body></html>`;
    apifyMock(() => htmlResponse(page));
    expectApiError(await catchErr(fetchPage(env, "https://cf.example.com/", noToken)), 502, "fetch_failed");
  });

  it("meta charset utf-16 is treated as utf-8", async () => {
    const page = '<html><head><meta charset="utf-16"></head><body><pre>Caf\u00e9 ' + "G C D\n".repeat(60) + "</pre></body></html>";
    apifyMock(() => new Response(new TextEncoder().encode(page), { headers: { "content-type": "text/html" } }));
    expect((await fetchPage(env, "https://x.example.com/", withToken)).text).toContain("Caf\u00e9");
  });

  it("a UTF-8 BOM wins over a wrong meta charset", async () => {
    const page = '<html><head><meta charset="windows-1252"></head><body><pre>Caf\u00e9 ' + "G C D\n".repeat(60) + "</pre></body></html>";
    const bytes = new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode(page)]);
    apifyMock(() => new Response(bytes, { headers: { "content-type": "text/html" } }));
    expect((await fetchPage(env, "https://x.example.com/", withToken)).text).toContain("Caf\u00e9");
  });

  it("sniffs <meta charset> when Content-Type has none", async () => {
    const head = new TextEncoder().encode('<html><head><meta charset="windows-1252"></head><body><pre>');
    const body = new Uint8Array([...head, ...new TextEncoder().encode("Caf"), 0xe9, ...new TextEncoder().encode(" " + "G C D\n".repeat(60) + "</pre></body></html>")]);
    apifyMock(() => new Response(body, { headers: { "content-type": "text/html" } }));
    const r = await fetchPage(env, "https://x.example.com/", withToken);
    expect(r.via).toBe("direct");
    expect(r.text).toContain("Caf\u00e9");
  });

  it("short text (< 200 chars) -> Apify, falling back to the item's text field", async () => {
    apifyMock(() => htmlResponse("<html><body><div id=app></div></body></html>"), [{ text: "plain text result" }]);
    const r = await fetchPage(env, "https://spa.example.com/", withToken);
    expect(r.via).toBe("apify");
    expect(r.text).toBe("plain text result");
  });

  it("no token -> 502 fetch_failed", async () => {
    apifyMock(() => htmlResponse("no", 403));
    expectApiError(await catchErr(fetchPage(env, "https://x.example.com/", noToken)), 502, "fetch_failed");
    expect(calls.some((c) => c.url.includes("apify"))).toBe(false);
  });

  it("defaults to getSecret(env, 'APIFY_TOKEN') when no getToken is passed", async () => {
    apifyMock(() => htmlResponse("no", 403));
    const r = await fetchPage(env, "https://x.example.com/");
    expect(r.via).toBe("apify");
    const call = calls.find((c) => c.url.startsWith("https://api.apify.com/"))!;
    expect(new Headers(call.init?.headers).get("authorization")).toBe(`Bearer ${env.APIFY_TOKEN}`);
  });

  it("Apify error -> 502 fetch_failed without leaking the token", async () => {
    mockFetch((url) => {
      if (url.startsWith("https://api.apify.com/")) return new Response(`bad token ${FAKE_TOKEN}`, { status: 401 });
      return htmlResponse("no", 403);
    });
    const e = await catchErr(fetchPage(env, "https://x.example.com/", withToken));
    expectApiError(e, 502, "fetch_failed");
    expect(String((e as Error).message)).not.toContain(FAKE_TOKEN);
    expect(JSON.stringify(e)).not.toContain(FAKE_TOKEN);
  });

  it("Apify empty dataset -> 502 fetch_failed", async () => {
    apifyMock(() => htmlResponse("no", 403), []);
    expectApiError(await catchErr(fetchPage(env, "https://x.example.com/", withToken)), 502, "fetch_failed");
  });

  it("Apify network error -> 502 fetch_failed", async () => {
    mockFetch((url) => {
      if (url.startsWith("https://api.apify.com/")) throw new TypeError(`connect failed Authorization: Bearer ${FAKE_TOKEN}`);
      return htmlResponse("no", 403);
    });
    const e = await catchErr(fetchPage(env, "https://x.example.com/", withToken));
    expectApiError(e, 502, "fetch_failed");
    expect(String((e as Error).message)).not.toContain(FAKE_TOKEN);
  });

  it("404 -> 502 fetch_failed without Apify", async () => {
    apifyMock(() => htmlResponse("Not found", 404));
    expectApiError(await catchErr(fetchPage(env, "https://x.example.com/", withToken)), 502, "fetch_failed");
    expect(calls.some((c) => c.url.includes("apify"))).toBe(false);
  });

  it("direct network error -> Apify", async () => {
    mockFetch((url) => {
      if (url.startsWith("https://api.apify.com/")) return Response.json([{ markdown: "ok from apify" }]);
      throw new TypeError("network down");
    });
    expect((await fetchPage(env, "https://x.example.com/", withToken)).text).toBe("ok from apify");
  });

  it("direct fetch that hangs past 15 s is aborted -> Apify", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      let signal: AbortSignal | undefined;
      mockFetch((url, init) => {
        if (url.startsWith("https://api.apify.com/")) return Response.json([{ markdown: "late but ok" }]);
        signal = init?.signal ?? undefined;
        return new Promise<Response>((_res, rej) => signal!.addEventListener("abort", () => rej(new Error("aborted"))));
      });
      const p = fetchPage(env, "https://slow.example.com/", withToken);
      await vi.advanceTimersByTimeAsync(14_999);
      expect(signal?.aborted).toBe(false);
      await vi.advanceTimersByTimeAsync(2);
      expect(signal?.aborted).toBe(true);
      expect((await p).text).toBe("late but ok");
    } finally {
      vi.useRealTimers();
    }
  });
});
