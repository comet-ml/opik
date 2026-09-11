import { v4 as uuidv4 } from "uuid";

import { AuthSendAs, ProviderAuthConfig } from "@/types/providers";

export type KeyValueEntry = {
  key: string;
  value: string;
  id: string;
};

export const AUTH_MODE_VALUES = ["api_key", "token"] as const;
export type AuthMode = (typeof AUTH_MODE_VALUES)[number];

export type AuthCredentialEntry = {
  key: string;
  value: string;
  secret: boolean;
  saved: boolean; // Loaded from a stored provider: its lock can never be removed, per the backend contract
  id: string;
};

export type AuthConfigFormValues = {
  authMode: AuthMode;
  authTokenUrl: string;
  authSendAs: AuthSendAs;
  authCredentials: AuthCredentialEntry[];
  authTokenField: string;
  authExpiresField: string;
  authFallbackTtl: string;
};

/** Field names the backend auto-locks; mirrored here so the lock flips visibly as the user types. */
export const AUTH_SECRET_KEY_PATTERN = /secret|password|key|token|credential/i;

// The one grant the UI supports at the moment. Hidden from the credentials list and injected on save.
// A grant_type row the user adds themselves — or one stored with a different value — wins over the
// injected default.
const OAUTH2_GRANT_TYPE = {
  key: "grant_type",
  value: "client_credentials",
  secret: false,
};

export const EMPTY_AUTH_FORM_VALUES: AuthConfigFormValues = {
  authMode: "api_key",
  authTokenUrl: "",
  authSendAs: "basic",
  authCredentials: [],
  authTokenField: "",
  authExpiresField: "",
  authFallbackTtl: "",
};

/** Loads a stored provider's auth config into form state (absent config -> static mode). */
export function authConfigToFormValues(
  authConfig: ProviderAuthConfig | undefined,
): AuthConfigFormValues {
  if (!authConfig) {
    return { ...EMPTY_AUTH_FORM_VALUES };
  }
  return {
    authMode: "token",
    authTokenUrl: authConfig.token_url ?? "",
    authSendAs: authConfig.send_as ?? "basic",
    authCredentials: (authConfig.credentials ?? [])
      .filter(
        (credential) =>
          credential.key !== OAUTH2_GRANT_TYPE.key ||
          credential.value !== OAUTH2_GRANT_TYPE.value,
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
  if (!credentials.some(({ key }) => key === OAUTH2_GRANT_TYPE.key)) {
    credentials.unshift({ ...OAUTH2_GRANT_TYPE });
  }

  const fallbackTtl = values.authFallbackTtl.trim();
  return {
    token_url: values.authTokenUrl.trim(),
    send_as: values.authSendAs,
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

export function oauth2CredentialRows(): AuthCredentialEntry[] {
  return [
    { key: "client_id", value: "", secret: false, saved: false, id: uuidv4() },
    {
      key: "client_secret",
      value: "",
      secret: true,
      saved: false,
      id: uuidv4(),
    },
  ];
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
