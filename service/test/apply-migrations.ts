import { applyD1Migrations, env } from "cloudflare:test";

declare module "cloudflare:test" {
  interface ProvidedEnv {
    DB: D1Database;
    MIGRATION_DB: D1Database;
    FILES: R2Bucket;
    IMPORT_QUEUE: Queue<{ jobId: string; idx: number }>;
    /** Type only, to match Env: miniflare binds no Workers AI; voice tests inject a fake AI.run. */
    AI: Ai;
    SETUP_TOKEN: string;
    HOOK_SECRET?: string;
    PUBLIC_HOST: string;
    SECRETS_KEY?: string;
    INWORLD_API_KEY?: string;
    OPENAI_API_KEY?: string;
    APIFY_TOKEN?: string;
    TEST_MIGRATIONS: D1Migration[];
  }
}

await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
