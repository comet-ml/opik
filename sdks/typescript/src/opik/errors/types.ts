/**
 * Options for creating an error.
 */
export interface ErrorOptions {
  message: string;
  code: string;
  statusCode?: number;
  details?: Record<string, unknown>;
}
