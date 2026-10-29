import { describe, expect, it } from "vitest";
import { parseExperimentReturnHref } from "./parseExperimentReturnHref";

const EXPERIMENT_PATH = "/ws/projects/p1/experiments/d1/compare";
const EXPERIMENT_SEARCH = "?experiments=%5B%22e1%22%5D&row=r1&filters=%5B%5D";
const EXPERIMENT_PARAMS = {
  workspaceName: "ws",
  projectId: "p1",
  datasetId: "d1",
};

describe("parseExperimentReturnHref", () => {
  it("returns the experiment path and search on a root basepath", () => {
    expect(
      parseExperimentReturnHref(`${EXPERIMENT_PATH}${EXPERIMENT_SEARCH}`, "/"),
    ).toEqual({ params: EXPERIMENT_PARAMS, searchStr: EXPERIMENT_SEARCH });
  });

  it("strips a non-root basepath", () => {
    expect(
      parseExperimentReturnHref(
        `/opik${EXPERIMENT_PATH}${EXPERIMENT_SEARCH}`,
        "/opik",
      ),
    ).toEqual({ params: EXPERIMENT_PARAMS, searchStr: EXPERIMENT_SEARCH });
  });

  it.each([
    ["starts with the basepath", "opik-demo"],
    ["equals the basepath", "opik"],
  ])(
    "keeps a workspace name that %s under a non-root basepath",
    (_, workspaceName) => {
      expect(
        parseExperimentReturnHref(
          `/opik/${workspaceName}/projects/p1/experiments/d1/compare`,
          "/opik",
        ),
      ).toEqual({
        params: { ...EXPERIMENT_PARAMS, workspaceName },
        searchStr: "",
      });
    },
  );

  it("decodes encoded path segments", () => {
    expect(
      parseExperimentReturnHref(
        "/my%20ws/projects/p1/experiments/d1/compare",
        "/",
      ),
    ).toEqual({
      params: { ...EXPERIMENT_PARAMS, workspaceName: "my ws" },
      searchStr: "",
    });
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
    ["misplaced experiments segment", "/ws/projects/p1/foo/experiments/bar"],
    ["unknown experiments route", "/ws/projects/p1/experiments/d1/not-a-route"],
    ["experiments list", "/ws/projects/p1/experiments/"],
    ["malformed encoding", "/ws%E0%A4%A/projects/p1/experiments/d1/compare"],
  ])("rejects %s input", (_, href) => {
    expect(parseExperimentReturnHref(href, "/")).toBeNull();
  });

  it("rejects paths outside the basepath", () => {
    expect(parseExperimentReturnHref(EXPERIMENT_PATH, "/opik")).toBeNull();
  });
});
