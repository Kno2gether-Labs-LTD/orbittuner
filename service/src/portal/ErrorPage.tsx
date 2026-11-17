import type { Context } from "hono";
import type { FC } from "hono/jsx";
import type { AppEnv } from "../auth/devices";
import { AuthShell } from "./Layout";
import { render, type PageStatus } from "./render";

const COPY: Record<number, { title: string; text: string }> = {
  400: { title: "That did not work", text: "The form was missing something or had a value we could not use. Go back and try again." },
  403: { title: "Please try again", text: "This form had expired or came from somewhere else. Reload the page you came from and send it again." },
  409: { title: "Not right now", text: "That change does not fit the song or account as it stands. Go back, reload and try again." },
  404: { title: "Not here", text: "There is nothing at this address. It may have been removed, or the link is wrong." },
  413: { title: "Too large", text: "That upload or form is bigger than the library accepts. Try a smaller file or split it up." },
  429: { title: "Slow down", text: "Too many attempts in a short time. Wait a few minutes and try again." },
  500: { title: "Something broke", text: "The library hit an unexpected error. Try again in a moment." },
};

/** Clearer words for specific codes than the status alone gives. */
const CODE_COPY: Record<string, { title: string; text: string }> = {
  forbidden: { title: "Not yours to change", text: "Your role cannot do that. Only the owner manages roles, settings and staff accounts." },
  song_published: { title: "Hide it first", text: "Published songs cannot be deleted. Hide the song, then delete it from the Hidden tab." },
  needs_google: { title: "Needs Google sign-in", text: "Only an account signed in with Google can become an admin." },
  invalid_expiry: { title: "Check the date", text: "A Plus end date must be a real day, after today and within ten years. Leave it empty for no end date." },
};

const ErrorView: FC<{ status: number; code: string }> = ({ status, code }) => {
  const copy = CODE_COPY[code] ?? COPY[status] ?? COPY[500];
  return (
    <AuthShell title={copy.title}>
      <div class="card" data-error={code}>
        <p class="faint">Error {status}</p>
        <h1>{copy.title}</h1>
        <p>{copy.text}</p>
        <a class="btn primary" href="/portal">
          Back to the library
        </a>
      </div>
    </AuthShell>
  );
};

/** True when an error on this request should be an HTML page rather than JSON. */
export function wantsPortalHtml(c: Context): boolean {
  return c.req.path.startsWith("/portal") && !(c.req.header("accept") ?? "").includes("application/json");
}

export function portalError(c: Context<AppEnv>, status: number, code: string) {
  const s = (COPY[status] ? status : 500) as PageStatus;
  return render(c, ErrorView({ status: s, code }), s);
}
