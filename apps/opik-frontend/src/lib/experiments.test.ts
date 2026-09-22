import { describe, expect, it } from "vitest";

import {
  buildExperimentName,
  EXPERIMENT_TAB,
  formatPromptVersionLabel,
  getAvailableExperimentTabs,
  isExperimentTabId,
  suggestNextExperimentName,
} from "./experiments";
import { EVALUATION_METHOD, Experiment } from "@/types/datasets";

const experiment = (overrides: Partial<Experiment> = {}) =>
  ({
    id: "e1",
    name: "Geo experiment",
    dataset_id: "d1",
    dataset_name: "geography_questions",
    ...overrides,
  }) as Experiment;

const testSuiteExperiment = () =>
  experiment({ evaluation_method: EVALUATION_METHOD.TEST_SUITE });

describe("experiments utilities", () => {
  // The page reads `tab` straight off the URL, so this guard is what stands between an arbitrary
  // query value and the tab state.
  describe("isExperimentTabId", () => {
    it("accepts every known tab id", () => {
      Object.values(EXPERIMENT_TAB).forEach((id) =>
        expect(isExperimentTabId(id)).toBe(true),
      );
    });

    it("rejects an unknown tab name", () => {
      expect(isExperimentTabId("traces")).toBe(false);
      expect(isExperimentTabId("")).toBe(false);
    });

    it("rejects values a URL parser can hand back that aren't strings", () => {
      [undefined, null, 0, 42, true, {}, ["items"]].forEach((value) =>
        expect(isExperimentTabId(value)).toBe(false),
      );
    });
  });

  describe("getAvailableExperimentTabs", () => {
    it("exposes every tab for a regular experiment", () => {
      expect(getAvailableExperimentTabs([experiment()])).toEqual([
        EXPERIMENT_TAB.items,
        EXPERIMENT_TAB.insights,
        EXPERIMENT_TAB.config,
        EXPERIMENT_TAB.scores,
        EXPERIMENT_TAB.logs,
      ]);
    });

    it("puts logs last so it does not displace the existing tabs", () => {
      const tabs = getAvailableExperimentTabs([experiment()]);
      expect(tabs[tabs.length - 1]).toBe(EXPERIMENT_TAB.logs);
    });

    it("hides insights and feedback scores for a test-suite experiment but keeps logs", () => {
      expect(getAvailableExperimentTabs([testSuiteExperiment()])).toEqual([
        EXPERIMENT_TAB.items,
        EXPERIMENT_TAB.config,
        EXPERIMENT_TAB.logs,
      ]);
    });

    it("keeps logs when several experiments are compared", () => {
      expect(
        getAvailableExperimentTabs([experiment(), experiment({ id: "e2" })]),
      ).toContain(EXPERIMENT_TAB.logs);
    });

    it("hides feedback scores while no experiment has loaded yet", () => {
      const tabs = getAvailableExperimentTabs([]);
      expect(tabs).not.toContain(EXPERIMENT_TAB.scores);
      expect(tabs).toContain(EXPERIMENT_TAB.logs);
    });
  });

  describe("formatPromptVersionLabel", () => {
    it("prefers the sequential version number", () => {
      expect(
        formatPromptVersionLabel({
          prompt_name: "My Prompt",
          version_number: "v3",
          commit: "c96aa875",
        }),
      ).toBe("My Prompt (v3)");
    });

    it("falls back to the commit hash when no version number", () => {
      expect(
        formatPromptVersionLabel({
          prompt_name: "My Prompt",
          version_number: undefined,
          commit: "c96aa875",
        }),
      ).toBe("My Prompt (c96aa875)");
    });

    it("omits the parenthetical when neither version nor commit is present", () => {
      expect(
        formatPromptVersionLabel({
          prompt_name: "My Prompt",
          version_number: undefined,
          commit: "",
        }),
      ).toBe("My Prompt");
    });
  });
});

describe("buildExperimentName", () => {
  it("appends the lowercase column letter", () => {
    expect(buildExperimentName("concise", 0)).toBe("concise_a");
    expect(buildExperimentName("concise", 1)).toBe("concise_b");
    expect(buildExperimentName("concise", 2)).toBe("concise_c");
  });

  it("trims surrounding whitespace", () => {
    expect(buildExperimentName("  concise  ", 0)).toBe("concise_a");
  });

  it("keeps a run number the user typed, letter last", () => {
    expect(buildExperimentName("concise_02", 0)).toBe("concise_02_a");
  });
});

describe("suggestNextExperimentName", () => {
  it("starts repeats at 02", () => {
    expect(suggestNextExperimentName("concise", null)).toBe("concise_02");
  });

  it("increments only a counter it suggested itself", () => {
    expect(suggestNextExperimentName("concise_02", "concise_02")).toBe(
      "concise_03",
    );
    expect(suggestNextExperimentName("concise_09", "concise_09")).toBe(
      "concise_10",
    );
    expect(suggestNextExperimentName("concise_99", "concise_99")).toBe(
      "concise_100",
    );
  });

  it("leaves a number the user typed alone", () => {
    expect(suggestNextExperimentName("prompt_gpt_4", null)).toBe(
      "prompt_gpt_4_02",
    );
    expect(suggestNextExperimentName("llama_70", null)).toBe("llama_70_02");
    expect(suggestNextExperimentName("eval_2026", null)).toBe("eval_2026_02");
  });

  it("stops incrementing once the user edits the name", () => {
    expect(suggestNextExperimentName("gpt_4", "concise_03")).toBe("gpt_4_02");
  });

  it("keeps the padding width it already used", () => {
    expect(suggestNextExperimentName("concise_002", "concise_002")).toBe(
      "concise_003",
    );
  });

  it("trims before suggesting", () => {
    expect(suggestNextExperimentName("  concise  ", null)).toBe("concise_02");
  });
});
