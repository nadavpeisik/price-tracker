/**
 * A non-2xx response (#248). Status only: the UI branches on the status
 * (401 = no session, 403 = not admitted, 4xx = terminal, 5xx = retryable) and
 * never reads the ProblemDetail body — `detail` is prose, not a contract
 * (#231), so nothing here parses it.
 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, statusText: string) {
    super(`Request failed: ${status} ${statusText}`)
    this.name = 'ApiError'
    this.status = status
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}
