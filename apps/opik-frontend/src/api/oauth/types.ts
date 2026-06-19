export type OAuthWorkspaceInfo = {
  id: string;
  name: string;
};

export type OAuthAuthorizeContext = {
  client_name: string;
  // Backend serializes AuthorizeContext with @JsonInclude(NON_NULL), so this
  // field is omitted from the payload (arrives as undefined) when unset.
  client_logo_uri?: string | null;
  workspaces: OAuthWorkspaceInfo[];
  csrf_token: string;
};

// Snake_case on the wire; opik-backend's global ObjectMapper applies SnakeCaseStrategy.
export type OAuthConsentRequest = {
  client_id: string;
  redirect_uri: string;
  code_challenge: string;
  code_challenge_method: string;
  resource: string;
  state: string | null;
  workspace_id: string;
  workspace_name: string;
  csrf: string;
};

export type OAuthConsentResponse = {
  redirect_to: string;
};
