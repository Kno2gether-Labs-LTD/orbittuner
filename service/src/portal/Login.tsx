import type { FC } from "hono/jsx";
import { AuthShell, Csrf, Notice } from "./Layout";

export const LoginPage: FC<{ csrf: string; notice?: string; name?: string }> = ({ csrf, notice, name }) => (
  <AuthShell title="Sign in">
    <div class="card">
      <h1>Sign in</h1>
      <p>The owner portal for your OrbitTuner library.</p>
      {notice ? <Notice kind="err">{notice}</Notice> : null}
      <form method="post" action="/portal/login">
        <Csrf value={csrf} />
        <label class="field">
          <span>Name</span>
          <input class="input" name="name" required maxlength={100} autocomplete="username" value={name ?? ""} autofocus={!name} />
        </label>
        <label class="field">
          <span>Password</span>
          <input class="input" name="password" type="password" required autocomplete="current-password" autofocus={!!name} />
        </label>
        <button class="btn primary" type="submit">
          Sign in
        </button>
      </form>
    </div>
    <p class="auth-foot">The owner and admins sign in here. Everyone else uses the app.</p>
  </AuthShell>
);
