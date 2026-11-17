import path from "node:path";
import {
  defineWorkersConfig,
  readD1Migrations,
} from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig(async () => {
  const migrations = await readD1Migrations(path.join(__dirname, "migrations"));
  return {
    // Mirror wrangler Text rule: portal .css, .client.js and seed .cho files import as plain strings.
    plugins: [
      {
        name: "portal-text-assets",
        enforce: "pre" as const,
        resolveId(source: string, importer: string | undefined) {
          if (!importer || !/\.(css|client\.js|cho)$/.test(source)) return null;
          return path.resolve(path.dirname(importer), source) + "?raw";
        },
      },
    ],
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
      poolOptions: {
        workers: {
          singleWorker: true,
          main: "./src/index.ts",
          miniflare: {
            compatibilityDate: "2025-10-01",
            compatibilityFlags: ["nodejs_compat"],
            // MIGRATION_DB: an empty database the migration test builds from scratch.
            d1Databases: ["DB", "MIGRATION_DB"],
            r2Buckets: ["FILES"],
            // Producer only: tests drive the consumer by calling the exported queue() handler
            // directly, so miniflare never delivers messages in the background.
            queueProducers: { IMPORT_QUEUE: "orbittuner-import" },
            bindings: {
              TEST_MIGRATIONS: migrations,
              SETUP_TOKEN: "test-setup-token",
              HOOK_SECRET: "test-hook-secret",
              PUBLIC_HOST: "tuner.orbitapps.space",
              SECRETS_KEY: "ab".repeat(32), // fake test-only key
              INWORLD_API_KEY: "test-inworld-key-1234",
              OPENAI_API_KEY: "test-openai-key-5678",
              APIFY_TOKEN: "test-apify-token-9012",
            },
          },
        },
      },
    },
  };
});
