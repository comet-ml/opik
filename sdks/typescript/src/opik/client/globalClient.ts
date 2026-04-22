import type { OpikClient } from "./Client";

let _globalClient: OpikClient | null = null;

export function getGlobalClient(): OpikClient {
  if (!_globalClient) {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { Opik } = require("./Client") as { Opik: new () => OpikClient };
    _globalClient = new Opik();
  }
  return _globalClient;
}

export function setGlobalClient(client: OpikClient): void {
  _globalClient = client;
}

export function resetGlobalClient(): void {
  _globalClient = null;
}
