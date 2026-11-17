import type { FC } from "hono/jsx";
import { Csrf, Icon, PageHead } from "./Layout";

/**
 * Developer device: a one-time code that signs a test device, a debug build or a script into the
 * signed-in staff account (owner or admin) through POST /v1/pair. App users never pair; they get
 * an account automatically.
 */
export const PairPage: FC<{
  csrf: string;
  who: { name: string; role: "owner" | "admin" | "user" };
  host: string;
  result?: { code: string; expiresAt: number };
}> = ({ csrf, who, host, result }) => (
  <>
    <PageHead
      title="Developer device"
      sub="Sign a test phone, an emulator or a script into your own staff account. People using the app never need this: they get an account automatically."
    />
    <div class="pair">
      <section class="card card-pad">
        <h2>Pair as yourself</h2>
        <p>
          The device acts as <b>{who.name}</b> ({who.role === "owner" ? "owner" : "admin"}): it sees hidden songs, its additions publish straight away and AI is limited only by the global cap.
        </p>
        <ol class="steps">
          <li>
            <span class="step-main">Generate a code. It works once, for ten minutes.</span>
          </li>
          <li>
            <span>
              Send it from the device: <code>POST https://{host}/v1/pair</code> with <code>{"{\"code\": \"…\", \"deviceName\": \"Pixel test\"}"}</code>. The reply holds the device token.
            </span>
          </li>
          <li>
            <span>The device then appears under Devices, where the owner can revoke it at any time.</span>
          </li>
        </ol>
        <form method="post" action="/portal/pair" class="pair-go">
          <Csrf value={csrf} />
          <button class={result ? "btn" : "btn primary"} type="submit">
            <Icon name="code" />
            {result ? "New code" : "Generate a code"}
          </button>
        </form>
      </section>
      {result ? (
        <section class="card qr-card dev-code" aria-live="polite">
          <p class="for">One-time pairing code</p>
          <p class="code big" aria-label="Pairing code">
            {result.code}
          </p>
          <div class="timer" data-expires={String(result.expiresAt)} data-ttl={String(Math.max(1, result.expiresAt - Date.now()))}>
            <div class="bar">
              <i></i>
            </div>
            <p>
              Expires in <b data-left>10:00</b>. Works once.
            </p>
          </div>
          <div class="linkline">
            <label class="sr-only" for="pair-code">
              Pairing code
            </label>
            <input class="input" id="pair-code" readonly value={result.code} />
            <button class="btn sm" type="button" data-copy="#pair-code">
              Copy
            </button>
          </div>
        </section>
      ) : (
        <section class="card qr-card">
          <div class="empty">
            <Icon name="code" />
            <h3>The code appears here</h3>
            <p>Codes last ten minutes and pair exactly one device to your account.</p>
          </div>
        </section>
      )}
    </div>
  </>
);
