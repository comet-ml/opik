import { describe, expect, it } from "vitest";

import { formatExperimentSubtext } from "./formatExperimentSubtext";
import { formatDate } from "@/lib/date";
import { EVALUATION_METHOD, Experiment } from "@/types/datasets";

const createdAt = "2026-09-28T14:50:00Z";
const date = formatDate(createdAt);

const experiment = (overrides: Partial<Experiment> = {}) =>
  ({
    id: "e1",
    name: "Geo experiment",
    dataset_id: "d1",
    dataset_name: "geography_questions",
    created_at: createdAt,
    ...overrides,
  }) as Experiment;

const versionSummary = (versionName: string) => ({
  id: "v",
  version_hash: "h",
  version_name: versionName,
});

describe("formatExperimentSubtext", () => {
  it("joins date, test suite version, pass rate and prompts", () => {
    expect(
      formatExperimentSubtext(
        experiment({
          evaluation_method: EVALUATION_METHOD.TEST_SUITE,
          dataset_version_summary: versionSummary("v3"),
          pass_rate: 1,
          prompt_versions: [
            {
              id: "pv4",
              prompt_id: "p",
              prompt_name: "seat-booking",
              version_number: "v4",
            },
            {
              id: "pv1",
              prompt_id: "p",
              prompt_name: "seat-booking",
              version_number: "v1",
            },
          ],
        }),
      ),
    ).toBe(
      `${date} • Test suite v3 • Pass rate: 100% • Prompt: seat-booking (v1), seat-booking (v4)`,
    );
  });

  it("labels a legacy dataset version as Dataset and omits the missing pass rate", () => {
    expect(
      formatExperimentSubtext(
        experiment({ dataset_version_summary: versionSummary("v2") }),
      ),
    ).toBe(`${date} • Dataset v2`);
  });

  it("caps a pass rate below 1 at 99%", () => {
    expect(
      formatExperimentSubtext(
        experiment({
          evaluation_method: EVALUATION_METHOD.TEST_SUITE,
          pass_rate: 0.995,
        }),
      ),
    ).toBe(`${date} • Pass rate: 99%`);
  });

  it("keeps a 0% pass rate", () => {
    expect(
      formatExperimentSubtext(
        experiment({
          evaluation_method: EVALUATION_METHOD.TEST_SUITE,
          pass_rate: 0,
        }),
      ),
    ).toBe(`${date} • Pass rate: 0%`);
  });

  it("returns only the date when nothing else is known", () => {
    expect(formatExperimentSubtext(experiment())).toBe(date);
  });

  describe("withSourceName", () => {
    it("names the test suite before its version", () => {
      expect(
        formatExperimentSubtext(
          experiment({
            evaluation_method: EVALUATION_METHOD.TEST_SUITE,
            dataset_version_summary: versionSummary("v3"),
          }),
          { withSourceName: true },
        ),
      ).toBe(`${date} • Test suite: geography_questions v3`);
    });

    it("names a legacy dataset without a version", () => {
      expect(
        formatExperimentSubtext(experiment(), { withSourceName: true }),
      ).toBe(`${date} • Dataset: geography_questions`);
    });

    it("marks a deleted source", () => {
      expect(
        formatExperimentSubtext(
          experiment({
            evaluation_method: EVALUATION_METHOD.TEST_SUITE,
            dataset_name: undefined,
          }),
          { withSourceName: true },
        ),
      ).toBe(`${date} • Deleted test suite`);
    });
  });
});
