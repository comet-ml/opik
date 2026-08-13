import { describe, expect, it } from "vitest";

import {
  EXPERIMENT_TAB,
  formatPromptVersionLabel,
  getAvailableExperimentTabs,
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
