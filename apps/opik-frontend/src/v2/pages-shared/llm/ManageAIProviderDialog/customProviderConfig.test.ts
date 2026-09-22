import { describe, it, expect } from "vitest";

import {
  authConfigToFormValues,
  configStringToQueryParamsArray,
  convertHeadersForAPI,
  formValuesToAuthConfig,
  oauth2CredentialRows,
  queryParamsArrayToConfigString,
} from "./customProviderConfig";
import { ProviderAuthConfig } from "@/types/providers";

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
          { key: "grant_type", value: "client_credentials", secret: false },
          { key: "username", value: "svc", secret: false },
          { key: "password", value: "p", secret: true },
        ],
        token_field: "token",
        fallback_ttl_seconds: 90000,
      });
    });

    it("does not inject grant_type when the user provided their own", () => {
      const config = formValuesToAuthConfig(
        {
          ...tokenValues,
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
  });

  describe("oauth2CredentialRows", () => {
    it("seeds only the user-owned rows, locking the secret; grant_type is injected on save", () => {
      expect(
        oauth2CredentialRows().map((row) => [row.key, row.secret]),
      ).toEqual([
        ["client_id", false],
        ["client_secret", true],
      ]);
    });

    it("marks seeded rows as unsaved so their locks stay toggleable", () => {
      expect(oauth2CredentialRows().every((row) => !row.saved)).toBe(true);
    });
  });
});
