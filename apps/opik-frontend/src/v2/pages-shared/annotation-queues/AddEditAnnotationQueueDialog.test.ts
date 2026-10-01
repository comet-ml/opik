import { describe, expect, it } from "vitest";

import {
  buildAutomationPayload,
  formSchema,
} from "./AddEditAnnotationQueueDialog";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";

/**
 * The cap's rules, which only apply while automation is on. They are asserted through the schema
 * rather than the sheet because that is where they live: the sheet renders them, the schema decides.
 */
const form = (overrides: Record<string, unknown> = {}) => ({
  project_id: "project-1",
  name: "Hallucination review",
  scope: ANNOTATION_QUEUE_SCOPE.TRACE,
  comments_enabled: true,
  feedback_definition_names: [],
  annotators_per_item: 1,
  lock_timeout_minutes: 5,
  automation_enabled: true,
  automation_cap_enabled: false,
  automation_max_items: "1000",
  automation_groups: [
    {
      conditions: [{ name: "Hallucination", operator: ">", threshold: "0.5" }],
    },
  ],
  ...overrides,
});

const capIssues = (overrides: Record<string, unknown>) => {
  const result = formSchema.safeParse(form(overrides));
  return result.success
    ? []
    : result.error.issues.filter((issue) =>
        issue.path.includes("automation_max_items"),
      );
};

describe("AddEditAnnotationQueueDialog form schema", () => {
  describe("the automation cap", () => {
    it("accepts a whole number when it is ticked", () => {
      expect(
        capIssues({ automation_cap_enabled: true, automation_max_items: "25" }),
      ).toHaveLength(0);
    });

    it.each([
      ["empty", ""],
      ["zero", "0"],
      ["negative", "-1"],
      ["fractional", "2.5"],
      ["not a number", "many"],
    ])("rejects %s while it is ticked", (_label, value) => {
      expect(
        capIssues({
          automation_cap_enabled: true,
          automation_max_items: value,
        }),
      ).toHaveLength(1);
    });

    it("ignores the value entirely when it is unticked", () => {
      expect(
        capIssues({ automation_cap_enabled: false, automation_max_items: "" }),
      ).toHaveLength(0);
    });

    // Conditions and the cap are only the automation's business: a queue saved with automation off
    // must not be blocked by controls its author never opened.
    it("ignores the value when automation itself is off", () => {
      expect(
        capIssues({
          automation_enabled: false,
          automation_cap_enabled: true,
          automation_max_items: "",
        }),
      ).toHaveLength(0);
    });
  });
});

describe("the automation payload", () => {
  const groups = [
    {
      conditions: [
        { name: "accuracy", operator: "<" as const, threshold: "0.5" },
      ],
    },
  ];

  const build = (overrides: Record<string, unknown> = {}) =>
    buildAutomationPayload({
      isFeatureEnabled: true,
      hasStoredAutomation: false,
      enabled: false,
      capEnabled: false,
      maxItems: "100",
      groups,
      ...overrides,
    } as Parameters<typeof buildAutomationPayload>[0]);

  it("sends nothing while the feature is off, whatever the queue holds", () => {
    expect(build({ isFeatureEnabled: false })).toBeUndefined();
    expect(
      build({
        isFeatureEnabled: false,
        hasStoredAutomation: true,
        enabled: true,
      }),
    ).toBeUndefined();
  });

  it("sends nothing for a queue that never had automation", () => {
    // The API rejects a disabled automation whose stored conditions it cannot find, which would turn
    // creating a queue, or renaming one the SDK made, into a 400.
    expect(build()).toBeUndefined();
  });

  it("turns a stored automation off without touching its conditions or ceiling", () => {
    expect(build({ hasStoredAutomation: true, capEnabled: true })).toEqual({
      enabled: false,
    });
  });

  it("removes the ceiling when the cap is unticked and automation turned off at once", () => {
    // The cap control is hidden once automation is off, so the removal would otherwise be dropped and
    // the tick would be back on reopening.
    expect(build({ hasStoredAutomation: true })).toEqual({
      enabled: false,
      clear_max_items_in_queue: true,
    });
  });

  it("asks for the ceiling to be cleared when the cap is unticked", () => {
    // A null ceiling would be indistinguishable from "leave the stored one alone".
    expect(build({ enabled: true })).toEqual({
      enabled: true,
      clear_max_items_in_queue: true,
      conditions: {
        groups: [
          {
            conditions: [{ score_name: "accuracy", operator: "<", value: 0.5 }],
          },
        ],
      },
    });
  });

  it("sends the ceiling as a number when the cap is ticked", () => {
    expect(build({ enabled: true, capEnabled: true, maxItems: "250" })).toEqual(
      expect.objectContaining({ max_items_in_queue: 250 }),
    );
  });

  it("never sends a ceiling and a clear together", () => {
    const ticked = build({ enabled: true, capEnabled: true, maxItems: "250" });
    expect(ticked).not.toHaveProperty("clear_max_items_in_queue");
    const unticked = build({ enabled: true });
    expect(unticked).not.toHaveProperty("max_items_in_queue");
  });
});
