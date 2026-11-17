import { v4 as uuidv4 } from "uuid";

import {
  AuthSendAs,
  PROVIDER_TYPE,
  ProviderAuthConfig,
} from "@/types/providers";

export type KeyValueEntry = {
  key: string;
  value: string;
  id: string;
};

export const AUTH_MODE_VALUES = ["api_key", "token"] as const;
export type AuthMode = (typeof AUTH_MODE_VALUES)[number];

export const AUTH_TYPE_VALUES = [
  "oauth2",
  "service_account",
  "custom",
] as const;
export type AuthType = (typeof AUTH_TYPE_VALUES)[number];

export type AuthCredentialEntry = {
  key: string;
  value: string;
  secret: boolean;
  saved: boolean; // Loaded from a stored provider: its lock can never be removed, per the backend contract
  id: string;
};

export type AuthConfigFormValues = {
  authMode: AuthMode;
  authType: AuthType;
  authTokenUrl: string;
  authSendAs: AuthSendAs;
  authCredentials: AuthCredentialEntry[];
  authTokenField: string;
  authExpiresField: string;
  authFallbackTtl: string;
};

/** Field names the backend auto-locks; mirrored here so the lock flips visibly as the user types. */
export const AUTH_SECRET_KEY_PATTERN = /secret|password|key|token|credential/i;

// The OAuth2 type's grant. Hidden from its credentials list and injected on save. A grant_type row
// the user adds themselves — or one stored with a different value — wins over the injected default.
const OAUTH2_GRANT_TYPE = {
  key: "grant_type",
  value: "client_credentials",
  secret: false,
};

export const SERVICE_ACCOUNT_USERNAME_KEY = "username";
export const SERVICE_ACCOUNT_PASSWORD_KEY = "password";
// Service-account token endpoints commonly reply with the bare token and no lifetime
export const SERVICE_ACCOUNT_DEFAULT_TTL_SECONDS = "86400";

export const EMPTY_AUTH_FORM_VALUES: AuthConfigFormValues = {
  authMode: "api_key",
  authType: "oauth2",
  authTokenUrl: "",
  authSendAs: "basic",
  authCredentials: [],
  authTokenField: "",
  authExpiresField: "",
  authFallbackTtl: "",
};

const isOAuth2Grant = (credential: { key: string; value: string }) =>
  credential.key === OAUTH2_GRANT_TYPE.key &&
  credential.value === OAUTH2_GRANT_TYPE.value;

/**
 * The type a stored recipe was saved with. Only exact matches of what the OAuth2 and service
 * account types save map back to them; anything else (e.g. a recipe configured through the API)
 * opens as Custom so nothing about it is hidden or rewritten.
 */
export function authTypeOf(authConfig: ProviderAuthConfig): AuthType {
  const credentials = authConfig.credentials ?? [];
  if (authConfig.send_as !== "basic") {
    return "custom";
  }
  if (credentials.some(isOAuth2Grant)) {
    return "oauth2";
  }
  const keys = credentials.map(({ key }) => key).sort();
  if (
    keys.length === 2 &&
    keys[0] === SERVICE_ACCOUNT_PASSWORD_KEY &&
    keys[1] === SERVICE_ACCOUNT_USERNAME_KEY
  ) {
    return "service_account";
  }
  return "custom";
}

/** Loads a stored provider's auth config into form state (absent config -> static mode). */
export function authConfigToFormValues(
  authConfig: ProviderAuthConfig | undefined,
): AuthConfigFormValues {
  if (!authConfig) {
    return { ...EMPTY_AUTH_FORM_VALUES };
  }
  const authType = authTypeOf(authConfig);
  return {
    authMode: "token",
    authType,
    authTokenUrl: authConfig.token_url ?? "",
    authSendAs: authConfig.send_as ?? "form",
    authCredentials: (authConfig.credentials ?? [])
      .filter(
        (credential) => authType !== "oauth2" || !isOAuth2Grant(credential),
      )
      .map((credential) => ({
        key: credential.key,
        value: credential.value,
        secret: credential.secret,
        saved: true,
        id: uuidv4(),
      })),
    authTokenField: authConfig.token_field ?? "",
    authExpiresField: authConfig.expires_field ?? "",
    authFallbackTtl:
      authConfig.fallback_ttl_seconds !== undefined &&
      authConfig.fallback_ttl_seconds !== null
        ? String(authConfig.fallback_ttl_seconds)
        : "",
  };
}

/**
 * Builds the auth_config payload from form state.
 *
 * Three cases, mirroring the headers convention:
 * 1. Token mode -> the full recipe (empty-key rows dropped)
 * 2. Static mode while editing a provider that had a config -> {} to clear it
 * 3. Static mode otherwise -> undefined (field omitted)
 */
export function formValuesToAuthConfig(
  formValues: Partial<AuthConfigFormValues>,
  options: { isEditing: boolean; hadAuthConfig: boolean },
): ProviderAuthConfig | Record<string, never> | undefined {
  const values: AuthConfigFormValues = {
    ...EMPTY_AUTH_FORM_VALUES,
    ...formValues,
  };
  if (values.authMode !== "token") {
    return options.isEditing && options.hadAuthConfig ? {} : undefined;
  }

  const credentials = values.authCredentials
    .filter((credential) => credential.key.trim().length > 0)
    .map((credential) => ({
      key: credential.key.trim(),
      value: credential.value,
      secret: credential.secret,
    }));
  if (
    values.authType === "oauth2" &&
    !credentials.some(({ key }) => key === OAUTH2_GRANT_TYPE.key)
  ) {
    credentials.unshift({ ...OAUTH2_GRANT_TYPE });
  }

  const fallbackTtl = values.authFallbackTtl.trim();
  return {
    token_url: values.authTokenUrl.trim(),
    // OAuth2 and service accounts both send their id/secret pair as HTTP Basic
    send_as: values.authType === "custom" ? values.authSendAs : "basic",
    credentials,
    ...(values.authTokenField.trim() && {
      token_field: values.authTokenField.trim(),
    }),
    ...(values.authExpiresField.trim() && {
      expires_field: values.authExpiresField.trim(),
    }),
    ...(fallbackTtl && { fallback_ttl_seconds: Number(fallbackTtl) }),
  };
}

const credentialRow = (key: string, secret: boolean): AuthCredentialEntry => ({
  key,
  value: "",
  secret,
  saved: false,
  id: uuidv4(),
});

/**
 * Credential rows after switching type. OAuth2 and service account start from their own rows;
 * Custom keeps the current ones as a starting point, surfacing the grant OAuth2 injected on save
 * so the recipe behaves the same until the user edits it.
 */
export function credentialRowsForType(
  nextType: AuthType,
  previousType: AuthType,
  currentRows: AuthCredentialEntry[],
): AuthCredentialEntry[] {
  if (nextType === "oauth2") {
    // grant_type is not a row: it is injected on save
    return [
      credentialRow("client_id", false),
      credentialRow("client_secret", true),
    ];
  }
  if (nextType === "service_account") {
    return [
      credentialRow(SERVICE_ACCOUNT_USERNAME_KEY, false),
      credentialRow(SERVICE_ACCOUNT_PASSWORD_KEY, true),
    ];
  }
  if (
    previousType === "oauth2" &&
    !currentRows.some(({ key }) => key === OAUTH2_GRANT_TYPE.key)
  ) {
    return [
      { ...OAUTH2_GRANT_TYPE, saved: false, id: uuidv4() },
      ...currentRows,
    ];
  }
  return currentRows;
}

/**
 * Converts header array from form state to API-compatible object format.
 *
 * Three cases:
 * 1. Non-empty array → Convert to object, filtering empty keys
 * 2. Empty array when editing → Return {} to clear headers from backend
 * 3. Empty array when creating → Return undefined (don't send headers field)
 */
export function convertHeadersForAPI(
  headersArray: Array<{ key: string; value: string }> | undefined,
  isEditing: boolean,
): Record<string, string> | undefined {
  if (headersArray === undefined) {
    return undefined;
  }

  if (headersArray.length > 0) {
    return headersArray.reduce<Record<string, string>>((acc, header) => {
      const trimmedKey = header.key.trim();
      if (trimmedKey) {
        acc[trimmedKey] = header.value;
      }
      return acc;
    }, {});
  }

  if (isEditing) {
    return {};
  }

  return undefined;
}

// Built-in providers whose backend client applies the stored static `headers` map to every
// upstream call (OpenAIClientGenerator serves both). Other built-in providers ignore `headers`,
// so the field is only offered for these.
export const PROVIDERS_WITH_HEADERS: readonly PROVIDER_TYPE[] = [
  PROVIDER_TYPE.OPEN_AI,
  PROVIDER_TYPE.OPEN_ROUTER,
];

export const supportsProviderHeaders = (
  provider: PROVIDER_TYPE | string | undefined | null,
): boolean =>
  Boolean(provider) &&
  (PROVIDERS_WITH_HEADERS as readonly string[]).includes(provider as string);

/**
 * Headers payload for a built-in provider key: undefined for providers that ignore `headers`,
 * otherwise the same rules as {@link convertHeadersForAPI}. On create, an all-blank list is
 * omitted rather than sent as {}.
 */
export function builtInProviderHeadersForAPI(
  provider: PROVIDER_TYPE | string | undefined | null,
  headersArray: Array<{ key: string; value: string }> | undefined,
  isEditing: boolean,
): Record<string, string> | undefined {
  if (!supportsProviderHeaders(provider)) {
    return undefined;
  }

  const headers = convertHeadersForAPI(headersArray, isEditing);

  if (!isEditing && headers && Object.keys(headers).length === 0) {
    return undefined;
  }

  return headers;
}

/**
 * Converts a key/value array to a JSON-encoded Map<String,String> string for
 * storage in the Custom LLM provider's `configuration.url_query_params` slot.
 * Returns undefined when nothing is configured so the key is omitted on create
 * (the backend decorator treats missing/blank as "no query params to append").
 */
export function queryParamsArrayToConfigString(
  queryParamsArray: Array<{ key: string; value: string }> | undefined,
): string | undefined {
  if (!queryParamsArray || queryParamsArray.length === 0) {
    return undefined;
  }
  const filtered = queryParamsArray.reduce<Record<string, string>>(
    (acc, entry) => {
      const trimmedKey = entry.key.trim();
      if (trimmedKey) {
        acc[trimmedKey] = entry.value;
      }
      return acc;
    },
    {},
  );
  return Object.keys(filtered).length > 0
    ? JSON.stringify(filtered)
    : undefined;
}

/** Inverse of queryParamsArrayToConfigString for loading existing providers. */
export function configStringToQueryParamsArray(
  raw: string | undefined,
): KeyValueEntry[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as Record<string, string>;
    return Object.entries(parsed).map(([key, value]) => ({
      key,
      value,
      id: uuidv4(),
    }));
  } catch {
    return [];
  }
}
