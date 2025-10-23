import { RequestOptions } from "@/types/request";

/**
 * Core configuration interface for Opik SDK
 */
export interface OpikConfig {
  apiKey: string;
  apiUrl?: string;
  projectName: string;
  workspaceName: string;
  requestOptions?: RequestOptions;
  batchDelayMs?: number;
  holdUntilFlush?: boolean;
}

/**
 * Extended configuration interface with additional constructor options
 */
export interface ConstructorOpikConfig extends OpikConfig {
  headers?: Record<string, string>;
}
