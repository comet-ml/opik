export interface PythonSdkClient {
  createProject(args: { name: string; workspace?: string }): Promise<{ id: string; name: string }>;
  createTrace(args: {
    project_name: string;
    name: string;
    input: string;
    output: string;
    workspace?: string;
  }): Promise<{ id: string; name: string; project_id: string }>;
}

export class PythonSdkBridgeError extends Error {
  readonly status: number;
  readonly endpoint: string;
  readonly detail: unknown;

  constructor(args: { status: number; endpoint: string; detail: unknown; message: string }) {
    super(args.message);
    this.name = 'PythonSdkBridgeError';
    this.status = args.status;
    this.endpoint = args.endpoint;
    this.detail = args.detail;
  }
}

const DEFAULT_BRIDGE_URL = 'http://localhost:5175';
const REQUEST_TIMEOUT_MS = 30_000;

export function makePythonSdkClient(opts: { bridgeUrl?: string } = {}): PythonSdkClient {
  const bridgeUrl = opts.bridgeUrl ?? process.env.OPIK_SDK_DRIVER_URL ?? DEFAULT_BRIDGE_URL;

  async function request<TResponse>(
    method: 'GET' | 'POST' | 'DELETE',
    path: string,
    body?: unknown,
  ): Promise<TResponse> {
    const endpoint = `${method} ${path}`;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), REQUEST_TIMEOUT_MS);
    try {
      const headers: Record<string, string> = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      // Read OPIK_API_KEY at request time so the minted key from globalSetup
      // is picked up after the bridge has already spawned.
      const apiKey = process.env.OPIK_API_KEY;
      if (apiKey) headers['X-Opik-Api-Key'] = apiKey;
      const res = await fetch(`${bridgeUrl}${path}`, {
        method,
        headers: Object.keys(headers).length > 0 ? headers : undefined,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: ctrl.signal,
      });
      if (res.ok) {
        return (await res.json()) as TResponse;
      }
      const text = await res.text();
      let detail: unknown;
      try {
        detail = JSON.parse(text);
      } catch {
        detail = text;
      }
      const summary =
        typeof detail === 'object' && detail !== null && 'detail' in detail
          ? JSON.stringify((detail as { detail: unknown }).detail)
          : text.slice(0, 200);
      throw new PythonSdkBridgeError({
        status: res.status,
        endpoint,
        detail,
        message: `opik-sdk-driver ${endpoint} -> ${res.status}: ${summary}`,
      });
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    async createProject({ name, workspace }) {
      return request<{ id: string; name: string }>('POST', '/projects', { name, workspace });
    },
    async createTrace(args) {
      return request<{ id: string; name: string; project_id: string }>('POST', '/traces', args);
    },
  };
}
