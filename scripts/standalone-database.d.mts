export interface DatabaseGate {
  configPath: string;
  disconnect(): void;
  reconnect(): void;
  close(): Promise<void>;
}
export function createDatabaseGate(config: string | undefined): Promise<DatabaseGate>;
export function databaseEvidence(config: string | undefined, guest: string): Promise<{
  revision: number; stateRows: number; idempotencyRows: number;
}>;
