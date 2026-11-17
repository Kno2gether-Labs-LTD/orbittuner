import type { FC } from "hono/jsx";
import { Csrf, Icon, PageHead, ago, initials } from "./Layout";

export type DeviceRow = {
  id: string;
  name: string | null;
  user_name: string | null;
  role: string | null;
  created_at: number | null;
  last_seen_at: number | null;
  revoked_at: number | null;
};

export const DevicesPage: FC<{ csrf: string; devices: DeviceRow[] }> = ({ csrf, devices }) => {
  const active = devices.filter((d) => d.revoked_at == null).length;
  return (
    <>
      <PageHead
        title="Devices"
        sub={`${active} active ${active === 1 ? "device" : "devices"}. Revoking signs a device out at its next sync; its songs stay on it, but it stops receiving updates.`}
        actions={
          <a class="btn primary" href="/portal/pair">
            <Icon name="plus" />
            Pair a device
          </a>
        }
      />
      <div class="card">
        {devices.length === 0 ? (
          <div class="empty">
            <Icon name="devices" />
            <h3>No devices yet</h3>
            <p>Pair a phone or tablet to start syncing the library.</p>
          </div>
        ) : (
          <div class="rows">
            <div class="row head-row" aria-hidden="true">
              <span>Device</span>
              <span>Person</span>
              <span>Last seen</span>
              <span>Status</span>
              <span></span>
            </div>
            {devices.map((d) => (
              <div class={d.revoked_at ? "row revoked" : "row"}>
                <div class="dev">
                  <span class="ic">
                    <Icon name="devices" />
                  </span>
                  <span>
                    <b>{d.name || "Unnamed device"}</b>
                    <small>Paired {ago(d.created_at)}</small>
                  </span>
                </div>
                <div class="person">
                  <span class={d.role === "owner" ? "av owner" : "av"}>{initials(d.user_name ?? "?")}</span>
                  <span>{d.user_name ?? "Removed person"}</span>
                </div>
                <span class="when" data-label="Last seen">
                  {ago(d.last_seen_at)}
                </span>
                <span class={d.revoked_at ? "status revoked" : "status published"}>{d.revoked_at ? "Revoked" : "Active"}</span>
                <div class="end">
                  {d.revoked_at ? null : (
                    <form method="post" action={`/portal/devices/${encodeURIComponent(d.id)}/revoke`} data-confirm={`Revoke ${d.name || "this device"}? It will need to be paired again.`}>
                      <Csrf value={csrf} />
                      <button class="btn sm danger" type="submit">
                        Revoke
                      </button>
                    </form>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );
};
