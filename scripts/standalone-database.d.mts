export interface DatabaseGate {
  configPath: string;
  disconnect(): void;
  reconnect(): void;
  close(): Promise<void>;
}
export function createDatabaseGate(config: string | undefined): Promise<DatabaseGate>;
/** Private copy changes only instance ID; removed after verify settles, including failure. */
export function withMismatchedInstallation<T>(
  config: string | undefined, verify: (privateConfig: string) => Promise<T>,
): Promise<T>;
export function databaseEvidence(config: string | undefined, guest: string): Promise<{
  revision: number; stateRows: number; idempotencyRows: number;
}>;
