export interface Env {
  DB: D1Database;
  FILES: R2Bucket;
  IMPORT_QUEUE: Queue<{ jobId: string; idx: number }>;
  /** Workers AI (Whisper speech-to-text for POST /v1/voice). */
  AI: Ai;
  SETUP_TOKEN: string;
  HOOK_SECRET?: string;
  PUBLIC_HOST: string;
  SECRETS_KEY?: string;
  INWORLD_API_KEY?: string;
  OPENAI_API_KEY?: string;
  ANTHROPIC_API_KEY?: string;
  APIFY_TOKEN?: string;
  CUSTOM_AI_KEY_1?: string;
  CUSTOM_AI_KEY_2?: string;
  INWORLD_BASE_URL?: string;
}
