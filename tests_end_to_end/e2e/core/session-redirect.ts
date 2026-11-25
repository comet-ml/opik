import { request as playwrightRequest, type APIRequestContext } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * `GET /v1/session/redirect/*` — the links the SDK prints after a run.
 *
 * Its own module rather than a method on `core/backend/client.ts`, for one
 * reason that is not organisational: this endpoint does NOT accept an Opik API
 * key. It answers `403 "Please login first"` to a request carrying only
 * `Authorization` + `Comet-Workspace`, which is exactly what every method on
 * that client sends — so a helper there could only ever observe the 403.
 * What it needs is a browser SESSION cookie, which only a Playwright context
 * holds, so the caller supplies the `APIRequestContext` and the identity comes
 * with it.
 *
 * Two shapes, and the pair is the point: `getTraceRedirect` goes through a
 * context that carries the session (`page.request`), and
 * `getTraceRedirectWithoutSession` goes through a throwaway context that
 * carries none. A spec asserting only the first could not tell an endpoint that
 * authenticates from one that redirects anybody who asks.
 */

/** `{ status, location }` without following the redirect — both halves are the claim. */
export interface SessionRedirectResult {
  status: number;
  /** The `Location` header verbatim, or `null` on a non-redirect answer. */
  location: string | null;
  /** The response body, for a failure message that names what the server said. */
  body: string;
}

/**
 * The `path` query param every `/v1/session/redirect/*` endpoint takes: the
 * API base URL, base64**url**.
 *
 * base64url and not base64 for the same reason `attachmentBasePath()` in the
 * backend client is: the backend decodes with `Base64.getUrlDecoder()`, which
 * rejects the `+` and `/` a standard encoder emits. This URL happens to encode
 * to the same characters either way, so the distinction is invisible here and
 * would not be on a deployment whose host produced one — which is precisely the
 * kind of thing to get right in the helper rather than per spec.
 */
export function sessionRedirectPathParam(): string {
  return Buffer.from(loadEnvConfig().apiBaseUrl, 'utf-8').toString('base64url');
}

/**
 * The `Location` a trace link must answer with, spelled out in full.
 *
 * Built here rather than asserted piecemeal in the spec because the WHOLE
 * string is the contract: 9742 moved the workspace lookup onto the configured
 * `reactService.url`, so the failure it could cause is a correct-looking URL
 * with the wrong workspace segment — which a `toContain(traceId)` assertion
 * passes happily.
 *
 * `workspaceName` defaults to the run's configured workspace, which is the
 * answer the no-param branch has to derive on its own.
 */
export function expectedTraceRedirectLocation(args: {
  projectId: string;
  traceId: string;
  workspaceName?: string;
}): string {
  const env = loadEnvConfig();
  const workspace = args.workspaceName ?? env.workspace;
  return (
    `${env.baseUrl}/${workspace}/projects/${args.projectId}/traces` +
    `?tab=logs&logsType=traces&trace=${args.traceId}`
  );
}

function traceRedirectUrl(args: {
  traceId: string;
  workspaceName?: string;
  omitPath?: boolean;
}): string {
  const env = loadEnvConfig();
  const query = new URLSearchParams({ trace_id: args.traceId });
  // Omitted on request rather than defaulted: `path` is a required query param
  // (the endpoint answers 400 without it), so "sent nothing" is a case a spec
  // has to be able to pose.
  if (!args.omitPath) query.set('path', sessionRedirectPathParam());
  if (args.workspaceName !== undefined) query.set('workspace_name', args.workspaceName);
  return `${env.apiBaseUrl}/v1/session/redirect/projects?${query}`;
}

/**
 * The trace redirect, through a context that carries the browser session.
 *
 * `maxRedirects: 0` so the 303 and its `Location` are returned instead of
 * followed — following it would turn an assertion about the backend's answer
 * into an assertion about whatever the frontend then did with it, which is a
 * separate question this spec asks separately.
 */
export async function getTraceRedirect(
  request: APIRequestContext,
  args: { traceId: string; workspaceName?: string; omitPath?: boolean },
): Promise<SessionRedirectResult> {
  const response = await request.get(traceRedirectUrl(args), { maxRedirects: 0 });
  return {
    status: response.status(),
    location: response.headers()['location'] ?? null,
    body: (await response.text()).slice(0, 300),
  };
}

/**
 * The same call with NO session cookie, but WITH a valid Opik API key.
 *
 * The API key is sent deliberately. "An unauthenticated stranger is refused" is
 * a weaker claim than the one that matters: a caller holding a perfectly good
 * Opik credential is still refused, because this endpoint is session-scoped and
 * the workspace it resolves is the SESSION's. A spec that sent nothing at all
 * could not tell those apart.
 *
 * Through a throwaway `APIRequestContext`, with its `storageState` set to an
 * explicitly EMPTY one. That argument is load-bearing and was found the hard
 * way: inside a worker, `playwright.request.newContext()` with no options
 * inherits the config's `use.storageState` — the `.auth/user.json` global-setup
 * minted — so the "no session" context arrives carrying `sessionToken` and the
 * endpoint answers 303. The assertion then reads as "the API key was accepted",
 * which is the opposite of the truth. Anything added here that needs a
 * session-free identity must keep this argument.
 *
 * Disposed in a `finally` so a failed assertion cannot leak the context.
 */
export async function getTraceRedirectWithoutSession(args: {
  traceId: string;
}): Promise<SessionRedirectResult> {
  const env = loadEnvConfig();
  const headers: Record<string, string> = { 'Comet-Workspace': env.workspace };
  if (env.apiKey) headers['Authorization'] = env.apiKey;

  const context = await playwrightRequest.newContext({
    extraHTTPHeaders: headers,
    storageState: { cookies: [], origins: [] },
  });
  try {
    const result = await getTraceRedirect(context, args);
    // Guards the one way this helper can silently stop testing what it says:
    // a future Playwright that ignores the empty storageState would hand back a
    // session, and every assertion downstream would pass for the wrong reason.
    const carried = (await context.storageState()).cookies.map((c) => c.name);
    if (carried.includes('sessionToken')) {
      throw new Error(
        'getTraceRedirectWithoutSession: the throwaway context carried a sessionToken — ' +
          `it inherited a browser session (cookies: ${carried.join(', ')}), so this call ` +
          'did not test the no-session path at all',
      );
    }
    return result;
  } finally {
    await context.dispose();
  }
}
