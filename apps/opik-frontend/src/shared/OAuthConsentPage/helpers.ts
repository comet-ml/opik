import get from "lodash/get";
import { OAuthConsentRequest, OAuthWorkspaceInfo } from "@/api/oauth/types";

export type ParsedParams = {
  client_id: string;
  redirect_uri: string;
  code_challenge: string;
  code_challenge_method: string;
  resource: string;
  state: string | null;
};

export const parseParams = (search: string): ParsedParams | null => {
  const q = new URLSearchParams(search);
  // response_type is required by the OAuth spec; we gate on its presence but
  // never forward it (the backend already validated it before this redirect).
  const required = [
    "client_id",
    "redirect_uri",
    "response_type",
    "code_challenge",
    "code_challenge_method",
    "resource",
  ] as const;
  for (const k of required) {
    if (!q.get(k)) return null;
  }
  return {
    client_id: q.get("client_id") as string,
    redirect_uri: q.get("redirect_uri") as string,
    code_challenge: q.get("code_challenge") as string,
    code_challenge_method: q.get("code_challenge_method") as string,
    resource: q.get("resource") as string,
    state: q.get("state"),
  };
};

// Both the Allow and Deny outcomes leave the SPA by assigning a client redirect target to
// window.location. The initial target is OAuth for the hosted MCP over HTTP transport:
// clients receive the callback on a hosted https endpoint or an RFC 8252 §7.3 loopback
// listener (http://127.0.0.1|localhost) — both http(s). We only ever navigate to an http(s)
// URL, which keeps script-executing schemes (javascript:/data:) off this origin regardless
// of what the backend validated. Custom-scheme native deep-links are out of scope for the
// HTTP-transport target; revisit this gate if that client model is added.
const isHttpRedirect = (url: URL): boolean =>
  url.protocol === "http:" || url.protocol === "https:";

// Allow navigates to the backend-built redirect_to (registered redirect_uri + auth code).
// Gated by the same http(s) check as Deny so the two outcomes share one scheme policy.
export const navigateToRedirect = (redirectTo: string): boolean => {
  try {
    if (!isHttpRedirect(new URL(redirectTo))) return false;
    window.location.href = redirectTo;
    return true;
  } catch {
    return false;
  }
};

// Deny navigates to the raw redirect_uri query param. This is safe because the consent
// card (and its Deny button) only render after GET /oauth/authorize/context succeeds, and
// that endpoint validates redirect_uri against the registered client
// (OAuthClientService#resolveForRedirect throws "invalid redirect_uri" on a mismatch). An
// unregistered redirect_uri therefore never reaches this function — the page shows the
// terminal error card instead. RFC 6749 §4.1.2.1.
export const denyAndRedirect = (
  redirectUri: string,
  state: string | null,
): boolean => {
  try {
    const url = new URL(redirectUri);
    if (!isHttpRedirect(url)) return false;
    url.searchParams.set("error", "access_denied");
    if (state) url.searchParams.set("state", state);
    window.location.href = url.toString();
    return true;
  } catch {
    return false;
  }
};

export const buildConsentRequest = (
  params: ParsedParams,
  workspace: OAuthWorkspaceInfo,
  csrf: string,
): OAuthConsentRequest => ({
  client_id: params.client_id,
  redirect_uri: params.redirect_uri,
  code_challenge: params.code_challenge,
  code_challenge_method: params.code_challenge_method,
  resource: params.resource,
  state: params.state,
  workspace_id: workspace.id,
  workspace_name: workspace.name,
  csrf,
});

export const extractErrorMessage = (error: unknown, fallback: string): string =>
  get(error, ["response", "data", "message"], fallback);
