import { describe, it, expect } from "vitest";
import { isLandingRoute } from "./landingRoutes";

describe("isLandingRoute", () => {
  it("matches cloud path", () => {
    expect(isLandingRoute("/opik/my-ws/get-started")).toBe(true);
  });

  it("matches OSS path", () => {
    expect(isLandingRoute("/my-ws/get-started")).toBe(true);
  });

  it("ignores trailing slash", () => {
    expect(isLandingRoute("/opik/my-ws/get-started/")).toBe(true);
  });

  it("ignores hash fragment on the caller's side (pathname only)", () => {
    // pathname excludes hash/search; hash = "#manual" never reaches here
    expect(isLandingRoute("/opik/my-ws/get-started")).toBe(true);
  });

  it("does not match /home (intentionally excluded)", () => {
    expect(isLandingRoute("/opik/my-ws/home")).toBe(false);
  });

  it("does not match other routes", () => {
    expect(isLandingRoute("/opik/my-ws/projects")).toBe(false);
    expect(isLandingRoute("/opik/my-ws/datasets")).toBe(false);
    expect(isLandingRoute("/opik/my-ws")).toBe(false);
    expect(isLandingRoute("/")).toBe(false);
  });

  it("does not match substrings that only contain the suffix elsewhere", () => {
    expect(isLandingRoute("/opik/my-ws/projects/get-started-guide")).toBe(
      false,
    );
  });
});
