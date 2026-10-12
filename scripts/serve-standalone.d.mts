export interface OwnedRuntime {
  /** Idempotent; waits for this child to exit and its listener to be released. */
  stop(): Promise<void>;
  done: Promise<Error | null>;
}
export function verifiedProject(report: string | undefined): Promise<{ directory: string; baseURL: string }>;
export function startVerifiedRuntime(
  report: string | undefined,
  config: string | undefined,
  options?: { signal?: AbortSignal },
): Promise<OwnedRuntime>;
