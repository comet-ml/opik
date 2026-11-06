import { describe, it, expect } from "vitest";

import {
  authConfigToFormValues,
  authTypeOf,
  builtInProviderHeadersForAPI,
  configStringToQueryParamsArray,
  convertHeadersForAPI,
  credentialRowsForType,
  formValuesToAuthConfig,
  queryParamsArrayToConfigString,
} from "./customProviderConfig";
import { PROVIDER_TYPE, ProviderAuthConfig } from "@/types/providers";

describe("customProviderConfig", () => {
  describe("queryParamsArrayToConfigString", () => {
    it("returns undefined when the array is undefined", () => {
      expect(queryParamsArrayToConfigString(undefined)).toBeUndefined();
    });

    it("returns undefined when the array is empty", () => {
      expect(queryParamsArrayToConfigString([])).toBeUndefined();
    });

    it("returns undefined when every key is blank", () => {
      expect(
        queryParamsArrayToConfigString([
          { key: "  ", value: "x" },
          { key: "", value: "y" },
        ]),
      ).toBeUndefined();
    });

    it("serializes single entry to a JSON map", () => {
      expect(
        queryParamsArrayToConfigString([
          { key: "api-version", value: "2024-08-01-preview" },
        ]),
      ).toBe('{"api-version":"2024-08-01-preview"}');
    });

    it("serializes multiple entries and skips blank keys", () => {
      const result = queryParamsArrayToConfigString([
        { key: "api-version", value: "2024-08-01-preview" },
        { key: "  ", value: "ignored" },
        { key: "other", value: "value" },
      ]);
      expect(result).toBeDefined();
      const parsed = JSON.parse(result as string);
      expect(parsed).toEqual({
        "api-version": "2024-08-01-preview",
        other: "value",
      });
    });

    it("trims whitespace in keys but preserves values verbatim", () => {
      const result = queryParamsArrayToConfigString([
        { key: "  api-version  ", value: "  with spaces  " },
      ]);
      expect(JSON.parse(result as string)).toEqual({
        "api-version": "  with spaces  ",
      });
    });
  });

  describe("configStringToQueryParamsArray", () => {
    it("returns an empty array when raw is undefined", () => {
      expect(configStringToQueryParamsArray(undefined)).toEqual([]);
    });

    it("returns an empty array when raw is empty", () => {
      expect(configStringToQueryParamsArray("")).toEqual([]);
    });

    it("returns an empty array when raw is not valid JSON", () => {
      expect(configStringToQueryParamsArray("{not json")).toEqual([]);
    });

    it("parses a well-formed JSON map and assigns a UUID id per entry", () => {
      const result = configStringToQueryParamsArray(
        '{"api-version":"2024-08-01-preview","other":"value"}',
      );
      expect(result).toHaveLength(2);
      expect(result.map(({ key, value }) => ({ key, value }))).toEqual([
        { key: "api-version", value: "2024-08-01-preview" },
        { key: "other", value: "value" },
      ]);
      // Each entry must have a non-empty id so React can key it.
      result.forEach((entry) => expect(entry.id.length).toBeGreaterThan(0));
      // IDs must be unique.
      expect(new Set(result.map((e) => e.id)).size).toBe(result.length);
    });

    it("round-trips cleanly with queryParamsArrayToConfigString", () => {
      const input = [
        { key: "api-version", value: "2024-08-01-preview", id: "a" },
        { key: "other", value: "value", id: "b" },
      ];
      const serialized = queryParamsArrayToConfigString(input);
      const restored = configStringToQueryParamsArray(serialized);
      expect(restored.map(({ key, value }) => ({ key, value }))).toEqual(
        input.map(({ key, value }) => ({ key, value })),
      );
    });
  });

  describe("convertHeadersForAPI", () => {
    it("returns undefined for undefined input", () => {
      expect(convertHeadersForAPI(undefined, false)).toBeUndefined();
      expect(convertHeadersForAPI(undefined, true)).toBeUndefined();
    });

    it("returns undefined for an empty array when creating", () => {
      expect(convertHeadersForAPI([], false)).toBeUndefined();
    });

    it("returns an empty object for an empty array when editing (clears the backend field)", () => {
      expect(convertHeadersForAPI([], true)).toEqual({});
    });

    it("converts a populated array to an object and skips blank keys", () => {
      expect(
        convertHeadersForAPI(
          [
            { key: "api-key", value: "secret" },
            { key: "   ", value: "ignored" },
            { key: "X-Other", value: "value" },
          ],
          false,
        ),
      ).toEqual({
        "api-key": "secret",
        "X-Other": "value",
      });
    });
  });

  describe("builtInProviderHeadersForAPI", () => {
    const rows = [
      { key: "HTTP-Referer", value: "https://example.com" },
      { key: "X-OpenRouter-Title", value: "My App" },
    ];

    it.each([PROVIDER_TYPE.OPEN_AI, PROVIDER_TYPE.OPEN_ROUTER])(
      "sends headers for %s",
      (provider) => {
        expect(builtInProviderHeadersForAPI(provider, rows, false)).toEqual({
          "HTTP-Referer": "https://example.com",
          "X-OpenRouter-Title": "My App",
        });
      },
    );

    it.each([
      PROVIDER_TYPE.ANTHROPIC,
      PROVIDER_TYPE.VERTEX_AI,
      PROVIDER_TYPE.CUSTOM,
      undefined,
    ])("omits headers for %s", (provider) => {
      expect(
        builtInProviderHeadersForAPI(provider, rows, false),
      ).toBeUndefined();
      expect(
        builtInProviderHeadersForAPI(provider, rows, true),
      ).toBeUndefined();
    });

    it("omits empty and all-blank rows on create", () => {
      expect(
        builtInProviderHeadersForAPI(PROVIDER_TYPE.OPEN_ROUTER, [], false),
      ).toBeUndefined();
      expect(
        builtInProviderHeadersForAPI(
          PROVIDER_TYPE.OPEN_ROUTER,
          [{ key: "  ", value: "" }],
          false,
        ),
      ).toBeUndefined();
    });

    it("sends {} on edit to clear stored headers", () => {
      expect(
        builtInProviderHeadersForAPI(PROVIDER_TYPE.OPEN_AI, [], true),
      ).toEqual({});
    });
  });

  describe("authConfigToFormValues", () => {
    it("maps an absent config to static mode", () => {
      const values = authConfigToFormValues(undefined);
      expect(values.authMode).toBe("api_key");
      expect(values.authCredentials).toEqual([]);
    });

    it("loads a stored config with rows marked as saved", () => {
      const stored: ProviderAuthConfig = {
        token_url: "https://auth.example.com/token",
        send_as: "basic",
        credentials: [
          { key: "client_id", value: "opik", secret: false },
          { key: "client_secret", value: "__SECRET__", secret: true },
        ],
        token_field: "access_token",
        expires_field: "expires_in",
        fallback_ttl_seconds: 3600,
      };

      const values = authConfigToFormValues(stored);

      expect(values.authMode).toBe("token");
      expect(values.authTokenUrl).toBe("https://auth.example.com/token");
      expect(values.authSendAs).toBe("basic");
      expect(values.authFallbackTtl).toBe("3600");
      expect(values.authCredentials).toHaveLength(2);
      expect(values.authCredentials.every((row) => row.saved)).toBe(true);
      expect(values.authCredentials[1].value).toBe("__SECRET__");
    });

    it("hides the injected client_credentials grant row but keeps a custom one visible", () => {
      const values = authConfigToFormValues({
        token_url: "https://auth.example.com/token",
        send_as: "basic",
        credentials: [
          { key: "grant_type", value: "client_credentials", secret: false },
          { key: "client_id", value: "opik", secret: false },
        ],
      });
      expect(values.authCredentials.map((row) => row.key)).toEqual([
        "client_id",
      ]);

      const custom = authConfigToFormValues({
        token_url: "https://auth.example.com/token",
        credentials: [{ key: "grant_type", value: "password", secret: false }],
      });
      expect(custom.authCredentials.map((row) => row.key)).toEqual([
        "grant_type",
      ]);
    });

    it("keeps a client_credentials grant visible on a recipe that opens as Custom", () => {
      const values = authConfigToFormValues({
        token_url: "https://auth.example.com/token",
        send_as: "form",
        credentials: [
          { key: "grant_type", value: "client_credentials", secret: false },
          { key: "client_id", value: "opik", secret: false },
        ],
      });

      expect(values.authType).toBe("custom");
      expect(values.authSendAs).toBe("form");
      expect(values.authCredentials.map((row) => row.key)).toEqual([
        "grant_type",
        "client_id",
      ]);
    });

    it("defaults an absent send_as to form, the backend's default", () => {
      const values = authConfigToFormValues({
        token_url: "https://auth.example.com/token",
        credentials: [{ key: "api_key", value: "k", secret: true }],
      });
      expect(values.authSendAs).toBe("form");
    });

    it("stringifies a zero fallback (fetch-per-call) rather than dropping it", () => {
      const values = authConfigToFormValues({
        token_url: "https://auth.example.com/token",
        credentials: [],
        fallback_ttl_seconds: 0,
      });
      expect(values.authFallbackTtl).toBe("0");
    });
  });

  describe("formValuesToAuthConfig", () => {
    const tokenValues = {
      authMode: "token" as const,
      authType: "custom" as const,
      authTokenUrl: " https://auth.example.com/token ",
      authSendAs: "form" as const,
      authCredentials: [
        { key: "username", value: "svc", secret: false, saved: false, id: "1" },
        { key: "password", value: "p", secret: true, saved: false, id: "2" },
        { key: "  ", value: "dropped", secret: false, saved: false, id: "3" },
      ],
      authTokenField: "token",
      authExpiresField: "",
      authFallbackTtl: "90000",
    };

    it("builds the recipe in token mode, trimming and dropping empty-key rows", () => {
      const config = formValuesToAuthConfig(tokenValues, {
        isEditing: false,
        hadAuthConfig: false,
      });

      expect(config).toEqual({
        token_url: "https://auth.example.com/token",
        send_as: "form",
        credentials: [
          { key: "username", value: "svc", secret: false },
          { key: "password", value: "p", secret: true },
        ],
        token_field: "token",
        fallback_ttl_seconds: 90000,
      });
    });

    it("injects grant_type and sends Basic for the OAuth2 type, whatever send_as the form holds", () => {
      const config = formValuesToAuthConfig(
        { ...tokenValues, authType: "oauth2" },
        { isEditing: false, hadAuthConfig: false },
      );

      expect(config).toMatchObject({
        send_as: "basic",
        credentials: [
          { key: "grant_type", value: "client_credentials", secret: false },
          { key: "username", value: "svc", secret: false },
          { key: "password", value: "p", secret: true },
        ],
      });
    });

    it("sends a service account as Basic with only its username and password", () => {
      const config = formValuesToAuthConfig(
        {
          ...tokenValues,
          authType: "service_account",
          authCredentials: [
            {
              key: "username",
              value: "svc",
              secret: false,
              saved: false,
              id: "1",
            },
            {
              key: "password",
              value: "p",
              secret: true,
              saved: false,
              id: "2",
            },
          ],
          authTokenField: "",
          authFallbackTtl: "86400",
        },
        { isEditing: false, hadAuthConfig: false },
      );

      expect(config).toEqual({
        token_url: "https://auth.example.com/token",
        send_as: "basic",
        credentials: [
          { key: "username", value: "svc", secret: false },
          { key: "password", value: "p", secret: true },
        ],
        fallback_ttl_seconds: 86400,
      });
    });

    it("does not inject grant_type when the user provided their own", () => {
      const config = formValuesToAuthConfig(
        {
          ...tokenValues,
          authType: "oauth2",
          authCredentials: [
            {
              key: "grant_type",
              value: "password",
              secret: false,
              saved: false,
              id: "1",
            },
          ],
        },
        { isEditing: false, hadAuthConfig: false },
      );

      expect((config as ProviderAuthConfig).credentials).toEqual([
        { key: "grant_type", value: "password", secret: false },
      ]);
    });

    it("defaults send_as to basic per the OAuth2 client credentials flow", () => {
      const config = formValuesToAuthConfig(
        { authMode: "token", authTokenUrl: "https://auth.example.com/token" },
        { isEditing: false, hadAuthConfig: false },
      );
      expect(config).toMatchObject({ send_as: "basic" });
    });

    it("returns {} to clear when switching back to static on an edited provider", () => {
      const config = formValuesToAuthConfig(
        { ...tokenValues, authMode: "api_key" },
        { isEditing: true, hadAuthConfig: true },
      );
      expect(config).toEqual({});
    });

    it("returns undefined in static mode when there is nothing to clear", () => {
      expect(
        formValuesToAuthConfig(
          { ...tokenValues, authMode: "api_key" },
          { isEditing: true, hadAuthConfig: false },
        ),
      ).toBeUndefined();
      expect(
        formValuesToAuthConfig(
          { ...tokenValues, authMode: "api_key" },
          { isEditing: false, hadAuthConfig: false },
        ),
      ).toBeUndefined();
    });

    it("round-trips a loaded config, preserving the secret sentinel for unchanged values", () => {
      const stored: ProviderAuthConfig = {
        token_url: "https://auth.example.com/token",
        send_as: "basic",
        credentials: [
          { key: "grant_type", value: "client_credentials", secret: false },
          { key: "client_id", value: "opik", secret: false },
          { key: "client_secret", value: "__SECRET__", secret: true },
        ],
        token_field: "access_token",
      };

      const roundTripped = formValuesToAuthConfig(
        authConfigToFormValues(stored),
        { isEditing: true, hadAuthConfig: true },
      );

      expect(roundTripped).toEqual(stored);
    });

    it.each<[string, ProviderAuthConfig]>([
      [
        "service account",
        {
          token_url: "https://auth.example.com/token",
          send_as: "basic",
          credentials: [
            { key: "username", value: "svc", secret: false },
            { key: "password", value: "__SECRET__", secret: true },
          ],
          fallback_ttl_seconds: 86400,
        },
      ],
      [
        "custom",
        {
          token_url: "https://auth.example.com/token",
          send_as: "json",
          credentials: [
            { key: "grant_type", value: "client_credentials", secret: false },
            { key: "api_key", value: "__SECRET__", secret: true },
          ],
          token_field: "result.jwt",
        },
      ],
    ])("round-trips a %s recipe unchanged", (_, stored) => {
      const roundTripped = formValuesToAuthConfig(
        authConfigToFormValues(stored),
        { isEditing: true, hadAuthConfig: true },
      );

      expect(roundTripped).toEqual(stored);
    });
  });

  describe("authTypeOf", () => {
    const recipe = (
      overrides: Partial<ProviderAuthConfig>,
    ): ProviderAuthConfig => ({
      token_url: "https://auth.example.com/token",
      send_as: "basic",
      credentials: [],
      ...overrides,
    });

    it.each<[string, ProviderAuthConfig, string]>([
      [
        "Basic with the client_credentials grant",
        recipe({
          credentials: [
            { key: "grant_type", value: "client_credentials", secret: false },
            { key: "client_id", value: "id", secret: false },
            { key: "client_secret", value: "s", secret: true },
          ],
        }),
        "oauth2",
      ],
      [
        "Basic with exactly username and password",
        recipe({
          credentials: [
            { key: "password", value: "p", secret: true },
            { key: "username", value: "u", secret: false },
          ],
        }),
        "service_account",
      ],
      [
        "Basic with a client pair but no grant (e.g. made through the API)",
        recipe({
          credentials: [
            { key: "client_id", value: "id", secret: false },
            { key: "client_secret", value: "s", secret: true },
          ],
        }),
        "custom",
      ],
      [
        "a service account with an extra row",
        recipe({
          credentials: [
            { key: "username", value: "u", secret: false },
            { key: "password", value: "p", secret: true },
            { key: "scope", value: "x", secret: false },
          ],
        }),
        "custom",
      ],
      [
        "the client_credentials grant sent as a form body",
        recipe({
          send_as: "form",
          credentials: [
            { key: "grant_type", value: "client_credentials", secret: false },
          ],
        }),
        "custom",
      ],
      [
        "no send_as (the backend's form default)",
        recipe({
          send_as: undefined,
          credentials: [
            { key: "username", value: "u", secret: false },
            { key: "password", value: "p", secret: true },
          ],
        }),
        "custom",
      ],
    ])("maps %s to %s", (_, config, expected) => {
      expect(authTypeOf(config)).toBe(expected);
    });
  });

  describe("credentialRowsForType", () => {
    const savedRow = {
      key: "client_id",
      value: "opik",
      secret: false,
      saved: true,
      id: "1",
    };

    it("seeds a service account's username and locked password", () => {
      expect(
        credentialRowsForType("service_account", "oauth2", [savedRow]).map(
          (row) => [row.key, row.secret, row.saved],
        ),
      ).toEqual([
        ["username", false, false],
        ["password", true, false],
      ]);
    });

    it("seeds OAuth2's user-owned rows with a locked secret; grant_type is injected on save", () => {
      expect(
        credentialRowsForType("oauth2", "service_account", []).map((row) => [
          row.key,
          row.secret,
          row.saved,
        ]),
      ).toEqual([
        ["client_id", false, false],
        ["client_secret", true, false],
      ]);
    });

    it("keeps the rows when switching to Custom, surfacing the grant OAuth2 injected", () => {
      expect(
        credentialRowsForType("custom", "oauth2", [savedRow]).map((row) => [
          row.key,
          row.value,
        ]),
      ).toEqual([
        ["grant_type", "client_credentials"],
        ["client_id", "opik"],
      ]);
      expect(
        credentialRowsForType("custom", "service_account", [savedRow]),
      ).toEqual([savedRow]);
    });
  });
});
