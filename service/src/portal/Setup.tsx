import type { FC } from "hono/jsx";
import { AuthShell, Csrf, Notice } from "./Layout";

export const SetupPage: FC<{ csrf: string; token: string; minPassword: number }> = ({ csrf, token, minPassword }) => (
  <AuthShell title="Set up">
    <div class="card">
      <h1>Welcome to your library</h1>
      <p>Create the owner account. You will use it to curate songs and pair the family's devices.</p>
      <form method="post" action="/portal/setup">
        <Csrf value={csrf} />
        <input type="hidden" name="token" value={token} />
        <label class="field">
          <span>Your name</span>
          <input class="input" name="name" required maxlength={100} autocomplete="username" autofocus />
        </label>
        <label class="field">
          <span>Password</span>
          <input class="input" name="password" type="password" required minlength={minPassword} autocomplete="new-password" />
          <small>At least {minPassword} characters. A short phrase is easier to remember.</small>
        </label>
        <button class="btn primary" type="submit">
          Create owner account
        </button>
      </form>
    </div>
    <p class="auth-foot">This page works once. After setup it disappears.</p>
  </AuthShell>
);

/** A promoted admin sets their portal sign-in name and password through an owner-issued link. */
export const AdminSetupPage: FC<{ csrf: string; token: string; name: string; minPassword: number; notice?: string }> = ({
  csrf, token, name, minPassword, notice,
}) => (
  <AuthShell title="Portal sign-in">
    <div class="card">
      <h1>Set up your portal sign-in</h1>
      <p>You are an admin of this library. Choose the name and password you will sign in to the portal with.</p>
      {notice ? <Notice kind="err">{notice}</Notice> : null}
      <form method="post" action="/portal/admin-setup">
        <Csrf value={csrf} />
        <input type="hidden" name="token" value={token} />
        <label class="field">
          <span>Sign-in name</span>
          <input class="input" name="name" required maxlength={100} autocomplete="username" value={name} />
        </label>
        <label class="field">
          <span>Password</span>
          <input class="input" name="password" type="password" required minlength={minPassword} autocomplete="new-password" autofocus />
          <small>At least {minPassword} characters. A short phrase is easier to remember.</small>
        </label>
        <button class="btn primary" type="submit">
          Save and sign in
        </button>
      </form>
    </div>
    <p class="auth-foot">This link works once and expires 24 hours after it was made.</p>
  </AuthShell>
);
