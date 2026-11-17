/**
 * The LLM and page-fetch modules load on first use instead of at Worker start-up. Besides keeping
 * cold starts lean, this matters for tests: the Workers test pool evaluates src/index.ts before a test
 * file's vi.mock() calls run, so a static import chain from index.ts would pin the real modules.
 */
export const loadConvert = () => import("./convert");
export const loadFetchPage = () => import("./fetchPage");
export const loadLlm = () => import("./llm");
