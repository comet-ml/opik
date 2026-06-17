import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  buildConsentRequest,
  denyAndRedirect,
  extractErrorMessage,
  parseParams,
  ParsedParams,
} from "./helpers";

const REQUIRED_SEARCH =
  "?client_id=abc&redirect_uri=https%3A%2F%2Fhost.example%2Fcb" +
  "&response_type=code&code_challenge=chal&code_challenge_method=S256" +
  "&resource=https%3A%2F%2Fapi.example";

describe("parseParams", () => {
  it("parses a complete request and decodes the values", () => {
    expect(parseParams(REQUIRED_SEARCH)).toEqual({
      client_id: "abc",
      redirect_uri: "https://host.example/cb",
      code_challenge: "chal",
      code_challenge_method: "S256",
      resource: "https://api.example",
      state: null,
    });
  });

  it("keeps state when present", () => {
    expect(parseParams(`${REQUIRED_SEARCH}&state=xyz`)?.state).toBe("xyz");
  });

  it("does not forward response_type", () => {
    expect(parseParams(REQUIRED_SEARCH)).not.toHaveProperty("response_type");
  });

  it.each([
    "client_id",
    "redirect_uri",
    "response_type",
    "code_challenge",
    "code_challenge_method",
    "resource",
  ])("returns null when %s is missing", (missing) => {
    const params = new URLSearchParams(REQUIRED_SEARCH);
    params.delete(missing);
    expect(parseParams(`?${params.toString()}`)).toBeNull();
  });

  it("returns null for an empty search string", () => {
    expect(parseParams("")).toBeNull();
  });
});

describe("denyAndRedirect", () => {
  const original = window.location;

  beforeEach(() => {
    Object.defineProperty(window, "location", {
      writable: true,
      value: { href: "" },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, "location", {
      writable: true,
      value: original,
    });
  });

  it("appends error=access_denied and navigates", () => {
    expect(denyAndRedirect("https://host.example/cb", null)).toBe(true);
    expect(window.location.href).toBe(
      "https://host.example/cb?error=access_denied",
    );
  });

  it("includes state when provided", () => {
    expect(denyAndRedirect("https://host.example/cb", "xyz")).toBe(true);
    expect(window.location.href).toBe(
      "https://host.example/cb?error=access_denied&state=xyz",
    );
  });

  it("preserves existing query params on the redirect_uri", () => {
    expect(denyAndRedirect("https://host.example/cb?foo=bar", null)).toBe(true);
    expect(window.location.href).toBe(
      "https://host.example/cb?foo=bar&error=access_denied",
    );
  });

  it("returns false and does not navigate on a malformed redirect_uri", () => {
    expect(denyAndRedirect("not a url", "xyz")).toBe(false);
    expect(window.location.href).toBe("");
  });
});

describe("buildConsentRequest", () => {
  const params: ParsedParams = {
    client_id: "abc",
    redirect_uri: "https://host.example/cb",
    code_challenge: "chal",
    code_challenge_method: "S256",
    resource: "https://api.example",
    state: "xyz",
  };

  it("maps params, workspace, and csrf into the wire shape", () => {
    expect(
      buildConsentRequest(params, { id: "ws-1", name: "Default" }, "csrf-123"),
    ).toEqual({
      client_id: "abc",
      redirect_uri: "https://host.example/cb",
      code_challenge: "chal",
      code_challenge_method: "S256",
      resource: "https://api.example",
      state: "xyz",
      workspace_id: "ws-1",
      workspace_name: "Default",
      csrf: "csrf-123",
    });
  });
});

describe("extractErrorMessage", () => {
  it("reads the backend message", () => {
    const error = { response: { data: { message: "boom" } } };
    expect(extractErrorMessage(error, "fallback")).toBe("boom");
  });

  it("falls back when the message is absent", () => {
    expect(extractErrorMessage(new Error("x"), "fallback")).toBe("fallback");
    expect(extractErrorMessage(undefined, "fallback")).toBe("fallback");
  });
});
