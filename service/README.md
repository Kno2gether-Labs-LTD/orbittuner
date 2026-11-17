# OrbitTuner song service

A Cloudflare Worker (TypeScript, Hono) with D1 for data and R2 for files. It serves the app's song library API (`/v1/...`) and the owner portal (`/portal`).

> Never commit `.env`, `.dev.vars`, tokens, database exports or backups.

## Local development

```sh
cd service
npm install
printf 'SETUP_TOKEN=local-dev-token\n' > .env && chmod 600 .env   # git-ignored; wrangler reads it
npx wrangler d1 migrations apply orbittuner --local
npx wrangler dev
```

`service/.env` holds local secrets (git-ignored, `chmod 600`; wrangler reads it in `dev`). Variable names only, never commit values: `SETUP_TOKEN`, `SECRETS_KEY` (64 hex chars, enables portal-stored keys), `INWORLD_API_KEY`, `INWORLD_BASE_URL` (optional override of the Inworld endpoint), `OPENAI_API_KEY`, `APIFY_TOKEN`. Add at least one gateway key to try AI import locally.

Open `http://localhost:8787/portal/setup?token=local-dev-token` to create the owner account, then use **Load sample songs** on the empty Library page.

The session cookie is `Secure`, so Safari will not keep the session on plain `http://localhost`. Use Chrome or Firefox, or run `npx wrangler dev --local-protocol https`.

## Tests and type check

```sh
npm test            # vitest in the Workers runtime (workerd), in-memory D1/R2
npx tsc --noEmit
```

## First deploy

Needs a Cloudflare login (`npx wrangler login`). Do this once.

```sh
cd service

# 1. Database. Paste the printed database_id into wrangler.jsonc (d1_databases[0].database_id).
npx wrangler d1 create orbittuner

# 2. File bucket.
npx wrangler r2 bucket create orbittuner-files

# 3. Import queue (must exist before the deploy), then the schema: migrations 0001 to 0006.
npx wrangler queues create orbittuner-import
npx wrangler d1 migrations apply orbittuner --remote

# 4. Setup token. Hex avoids +, / and = so the URL needs no encoding. The token exists only in
#    this shell: print the setup URL once, use it, then close the terminal. Never commit or log it.
TOKEN=$(openssl rand -hex 32)
printf '%s' "$TOKEN" | npx wrangler secret put SETUP_TOKEN
echo "Setup URL: https://tuner.orbitapps.space/portal/setup?token=$TOKEN"

# 5. AI secrets (optional but needed for AI import; see "Secrets" below), then deploy.
npx wrangler deploy
```

`wrangler.jsonc` routes the custom domain `tuner.orbitapps.space` (self-hosting: change `routes` and `PUBLIC_HOST` to your own domain) (the zone must be on the same Cloudflare account; the DNS record and certificate are created on deploy). Check it:

```sh
curl https://tuner.orbitapps.space/health     # {"ok":true}
```

Then the owner opens the setup URL printed in step 4 (once; then close the terminal), chooses a name and password, and can use **Load sample songs** (or **Settings > Sample songs**). Setup only works while no owner exists.

## Secrets

Set with `printf '%s' "$VAL" | npx wrangler secret put NAME` (the value stays out of shell history and files):

| Secret | Purpose |
|---|---|
| `SETUP_TOKEN` | One-time owner setup URL (first deploy). |
| `SECRETS_KEY` | Encrypts keys saved in the portal. Must be 64 hex chars: `openssl rand -hex 32`. |
| `INWORLD_API_KEY` | Inworld Router gateway (default first choice). |
| `OPENAI_API_KEY` | OpenAI gateway (default fallback). |
| `APIFY_TOKEN` | Apify, only for sites that block direct fetches. |

`INWORLD_BASE_URL` is an optional plain variable for local dev only. Back up `SECRETS_KEY`: rotating or losing it makes keys stored in the portal unreadable (the page shows them as unreadable and the Worker falls back to the deploy secrets above). Re-enter them in the portal afterwards.

## Rate limiting on sign-in (recommended)

The Worker throttles sign-in attempts per IP and per name+IP pair (IPv6 grouped by /64). Attempts are counted before the password is checked, and a successful sign-in clears the name+IP counter. There is intentionally no account-wide lockout, so nobody can lock the owner out from elsewhere. A distributed attacker can rotate IPs, so add a Cloudflare WAF rate-limiting rule as a second layer:

- Dashboard: the zone > Security > WAF > Rate limiting rules > Create rule.
- Match: URI Path equals `/portal/login` and method is `POST`.
- Characteristics: IP. Rate: 20 requests per 1 minute. Action: Block (or Managed Challenge).
- Plan caveat: the Free plan only allows short periods (for example 10 seconds). Use what your plan allows, such as 10 requests per 10 seconds.

## Backups

`wrangler d1 export` does not work on this database: D1 refuses to export databases that contain virtual tables, and `songs_fts` is an FTS5 table. Do not rely on it. Use the portal instead (signed in as the owner, **Import & export**):

- **Full JSON backup**: `/portal/backup.json`. Every song, plus the owner accounts' playlists and favourites. This is the primary backup.
- **Songs zip**: `/portal/export.zip`. Every song as `.cho` files plus a manifest of language and categories. A second, human-readable copy.

Download both regularly and keep them somewhere private, off the repo (they are personal data).

Restore into a fresh database:

1. `npx wrangler d1 create orbittuner`, paste the new `database_id` into `wrangler.jsonc`.
2. `npx wrangler d1 migrations apply orbittuner --remote` (0001 to 0006).
3. Deploy, then complete `/portal/setup` as in the first deploy (new `SETUP_TOKEN`).
4. In **Import & export**, import `orbittuner-backup-<date>.json` (or the songs zip). Songs are restored with their language and categories. The import path restores songs only: the playlists and favourites in the JSON are kept in the file but are not re-imported, and devices must be paired again.

## Import limit: `import_max_songs`

An import is parsed and validated in memory first, then written in chunks of 50 songs in D1 batches. Each song costs several statements (song row, version row, full-text index), and a Worker request may only make a limited number of D1 queries, so one unbounded upload could fail halfway. To keep an import all-or-nothing at the "too many" boundary, the Worker counts candidate songs before writing and rejects the whole upload with `413 too_many_songs` if it exceeds the limit. Duplicates are looked up with a single query, not one per song.

- Default: 200 songs per upload (`DEFAULT_IMPORT_MAX_SONGS`).
- Change it by setting the `import_max_songs` row in the `settings` table, for example:
  `npx wrangler d1 execute orbittuner --remote --command "INSERT INTO settings (key, value) VALUES ('import_max_songs', '300') ON CONFLICT(key) DO UPDATE SET value = excluded.value"`
- Raise it carefully and only if your plan's per-request D1 query limit allows it. Larger libraries are better imported as several archives of at most this size.

## AI import

### Portal page "AI & keys"

Owner only. It holds everything about AI conversion:

- **Gateways**: an ordered list; the first enabled gateway that answers is used, the next one is the fallback. Per gateway you can enable or disable it, reorder it, set the model, and press **Test**. Defaults: **Inworld Router** (`https://api.inworld.ai/v1`, model `auto`) first, **OpenAI** (`gpt-4.1-mini`) as fallback. **Anthropic** and one **custom OpenAI-compatible gateway** (https base URL, model, own key) are optional and off by default.
- **API keys**: replace or remove each key. Keys saved here are encrypted in D1 (needs `SECRETS_KEY`) and only the last 4 characters are ever shown. A key saved in the portal wins over the deploy secret of the same name; removing it falls back to the secret.
- **Spending limits**: `ai_daily_limit` (conversions per day for the whole service, default 100, `0` turns AI off, resets at midnight UTC) and `ai_max_output_tokens` (cap per reply, default 4000).
- **Apify**: the actor (`apify_actor`, default `apify~rag-web-browser`). Used only when a site refuses a direct fetch; the token is set under API keys.

### Ways to import with AI

- **Links**: **Import & export > Links**. Paste URLs; the Worker queues one message per link on the Cloudflare Queue `orbittuner-import` (consumed two at a time, up to 2 retries) and the page shows progress from the `import_jobs` / `import_job_items` tables (migration 0006). API: `POST /v1/import/links`, then poll `GET /v1/import/jobs/:id`.
- **Text files**: upload `.txt` in **Import & export** with **AI for text files** set to Auto (AI only when the chords look unclear), Always or Never.
- **Editor**: **Convert with AI** rewrites the text in the song editor (`POST /v1/convert`). Clients that preview a URL should save the returned `chordpro` with `POST /v1/songs {chordpro}`; sending the URL to `/v1/songs` again fetches the page and runs the AI a second time.

### Publishing rule

An import by the **owner** with model confidence of at least 0.8 is published at once. Anything else goes to **Review**: lower confidence, and every import by a **member**.

### Limits

- `ai_daily_limit`: service-wide AI conversions per day (above).
- `discover_daily_limit` (Settings > Daily discoveries, default 20): per member per day. Each link import and each AI text conversion uses one unit; owners are exempt.
- `ai_import_sync_max` (default 10): AI conversions of `.txt` files in a single upload; larger uploads fail with `ai_batch_limit`.
- `import_max_songs` still applies (see above).
- An item still queued 30 minutes after its job was created is marked `failed` with `timeout`. If the queue cannot be reached, the unsent links are marked `queue_unavailable` and members get their units back.
- Change any of these settings rows with `npx wrangler d1 execute orbittuner --remote --command "INSERT INTO settings (key, value) VALUES ('ai_import_sync_max', '20') ON CONFLICT(key) DO UPDATE SET value = excluded.value"`.
- The queue, the 4-minute per-item fetch-and-AI budget and the subrequest counts assume the **Workers Paid plan**.

### Cost notes

Each AI conversion is 1 to 3 model calls (a retry or repair pass adds calls), so watch `ai_daily_limit`. Apify is billed separately and is only called when a site blocks direct fetches.

## Sing a line (beta): `POST /v1/voice`

The app sends a short clip (a sung line, a spoken title, or a command such as "find the chords for Tumi Robe Nirobe by Rabindranath Tagore") and gets catalogue matches back.

- Request: `POST /v1/voice?langs=hi,bn&artists=Meera%20Sen` with an `audio/wav` body: 16 kHz mono PCM16, 1 to 20 s, at most 700 KB (else `400 bad_audio`). Hints are optional, comma-separated: `langs` at most 3 codes from `en hi bn ta te ml kn mr gu pa ur`, `artists` at most 5 names of at most 60 characters (else `400 bad_hints`).
- Response: `200 {intent: "lyrics"|"request"|"none", transcript, language, romanized, guesses: [{title, artist}], webQuery, results}`; `romanized` is the transcript in Latin script (the raw transcript when the LLM step fell back, `""` when no words were heard); each result is a song summary plus `match: "lyrics"|"title"`, at most 10. Results follow the `/v1/search` visibility rules (published songs, plus the caller's own; staff see everything).
- Pipeline: Workers AI Whisper `@cf/openai/whisper-large-v3-turbo` (binding `AI` in `wrangler.jsonc`; the first pass forces no language, Whisper detects it, and the hints go to the LLM step as tie-breakers and pick the language of the second pass below), then one json-mode call through the AI gateways (intent, romanized text, up to 5 title/artist guesses, web query), then full-text search over titles and lyrics. If the gateways are down or take longer than 12 s, the endpoint still answers 200, searching with the raw transcript. Voice LLM calls are not counted in `ai_daily_limit`; `voice_daily_global` is their cap. A clip can make up to two LLM calls (the second pass below), so `voice_daily_global` caps clips, and LLM calls at about twice that.
- Bengali/Hindi second pass: when the first pass heard words but found no catalogue match (never on silence) and a `bn`/`hi` hint (the first one in the list), or else a detected `bn`/`hi`, gives a language, the clip is transcribed once more with that language forced: Inworld `groq/whisper-large-v3` when `INWORLD_API_KEY` is set, else (or if it fails) Workers AI with `language` set, 10 s per provider. The LLM step and the search then run on that transcript, and the pass with matches is returned (the second when both have them). It starts only if the first pass took under 20 s and is cut off after 20 s; it is skipped when Workers AI already detected the same language and Inworld has no key, and it never costs another unit.
- A transcript with no words is `200 {intent: "none"}`. Audio is never logged or stored.
- Quota: one unit per accepted clip, kept in `search_usage` under the scopes `voice_user`, `voice_ip` and `voice_global` (migration 0014), separate from the web-search counters. A failure of every speech-to-text provider gives the unit back and answers `503 voice_unavailable`; a refused user or IP counter is `429 voice_limit_reached {limit, plan}`; the global breaker is `503 voice_unavailable`. Owner and admins have no user or IP limit.

| Setting | Default | Meaning |
|---|---|---|
| `voice_daily_anon` | 3 | Clips per UTC day for an anonymous account |
| `voice_daily_free` | 10 | For a Google-signed-in account |
| `voice_daily_plus` | 50 | For an account on Plus |
| `voice_daily_per_ip` | 40 | Shared by every non-staff account on one IP (IPv6 /64) |
| `voice_daily_global` | 1000 | Circuit breaker for the whole service (staff count too) |

Change them like other settings rows, e.g. `npx wrangler d1 execute orbittuner --remote --command "UPDATE settings SET value = '5' WHERE key = 'voice_daily_anon'"`. Workers AI is billed to the Cloudflare account (free daily allocation first).

Speech-to-text fallback: the `stt_order` setting (portal Settings, default `workers-ai,inworld`) lists the providers tried in order; an error, non-2xx answer or 15 s timeout falls through to the next, and only when all fail is it `503 voice_unavailable` (unit refunded). `inworld` calls `POST https://api.inworld.ai/stt/v1/transcribe` with the `INWORLD_API_KEY` secret (the same key as the AI gateway; no key means the provider is skipped) and model `groq/whisper-large-v3`, overridable with the `inworld_stt_model` setting. Inworld returns no no-speech data, so a transcript that is empty or only "thank you", "thanks for watching", "you" or "bye" counts as no words (this rule applies to Inworld only). Logs hold only `stt provider <id> failed: <reason>`; never the key or the audio.

## Sample songs

Six hand-written public-domain songs live in `src/seed/songs/*.cho` (Amazing Grace, Come Thou Fount of Every Blessing, Doxology, It Is Well with My Soul, When the Saints Go Marching In, Auld Lang Syne). `POST /portal/seed` (owner only, CSRF-protected) imports them through the normal import path, so running it again adds nothing.
