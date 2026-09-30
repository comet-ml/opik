import { describe, expect, it } from "vitest";
import { parseExperimentReturnHref } from "./parseExperimentReturnHref";

const EXPERIMENT_PATH = "/ws/projects/p1/experiments/d1/compare";
const EXPERIMENT_SEARCH = "?experiments=%5B%22e1%22%5D&row=r1&filters=%5B%5D";

describe("parseExperimentReturnHref", () => {
  it("returns the experiment path and search on a root basepath", () => {
    expect(
      parseExperimentReturnHref(`${EXPERIMENT_PATH}${EXPERIMENT_SEARCH}`, "/"),
    ).toEqual({ to: EXPERIMENT_PATH, searchStr: EXPERIMENT_SEARCH });
  });

  it("strips a non-root basepath", () => {
    expect(
      parseExperimentReturnHref(
        `/opik${EXPERIMENT_PATH}${EXPERIMENT_SEARCH}`,
        "/opik",
      ),
    ).toEqual({ to: EXPERIMENT_PATH, searchStr: EXPERIMENT_SEARCH });
  });

  it.each([
    ["missing", undefined],
    ["null", null],
    ["empty", ""],
    ["relative", "ws/projects/p1/experiments/d1/compare"],
    ["protocol-relative", "//evil.com/experiments/x"],
    ["backslash host", "/\\evil.com/experiments/x"],
    ["absolute url", "https://evil.com/ws/projects/p1/experiments/d1/compare"],
    ["non-experiment path", "/ws/projects/p1/test-suites/s1/items"],
  ])("rejects %s input", (_, href) => {
    expect(parseExperimentReturnHref(href, "/")).toBeNull();
  });

  it("rejects paths outside the basepath", () => {
    expect(parseExperimentReturnHref(EXPERIMENT_PATH, "/opik")).toBeNull();
  });
});
