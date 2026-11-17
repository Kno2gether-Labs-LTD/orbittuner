export class ApiError extends Error {
  /** Set by aiConvert: true once a model call was attempted for this request (no quota refund then). */
  modelCalled?: boolean;
  constructor(
    public readonly status: number,
    public readonly code: string,
    /** Extra JSON fields for the error body (e.g. {limit, plan}); `error` always wins. */
    public readonly details?: Record<string, unknown>,
  ) {
    super(code);
    this.name = "ApiError";
  }
}
