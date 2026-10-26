import { describe, it, expect } from "vitest";

import { COLUMN_EXPERIMENT_ID } from "@/types/shared";
import { DEFAULT_TRACE_LOGS_VIEW_CONFIG } from "@/v2/pages-shared/traces/TraceLogsView/TraceLogsView";

import {
  PLAYGROUND_LOGS_VIEW_CONFIG,
  placeExperimentAfterOutput,
} from "./playgroundLogsViewConfig";

describe("placeExperimentAfterOutput", () => {
  it("adds the experiment column right after output", () => {
    expect(placeExperimentAfterOutput(["input", "output", "tags"])).toEqual([
      "input",
      "output",
      COLUMN_EXPERIMENT_ID,
      "tags",
    ]);
  });

  it("moves an existing experiment column instead of adding a second one", () => {
    expect(
      placeExperimentAfterOutput([
        "input",
        "output",
        "tags",
        COLUMN_EXPERIMENT_ID,
      ]),
    ).toEqual(["input", "output", COLUMN_EXPERIMENT_ID, "tags"]);
  });
});

describe("PLAYGROUND_LOGS_VIEW_CONFIG", () => {
  it.each(["defaultColumns", "defaultColumnsOrder"] as const)(
    "shows the experiment column right after output in %s",
    (key) => {
      const columns = PLAYGROUND_LOGS_VIEW_CONFIG[key];
      expect(columns[columns.indexOf("output") + 1]).toBe(COLUMN_EXPERIMENT_ID);
      expect(columns.filter((id) => id === COLUMN_EXPERIMENT_ID)).toHaveLength(
        1,
      );
    },
  );

  it("keeps the other hosts' storage namespace and defaults", () => {
    expect(PLAYGROUND_LOGS_VIEW_CONFIG.storageNamespace).toBe(
      DEFAULT_TRACE_LOGS_VIEW_CONFIG.storageNamespace,
    );
    expect(DEFAULT_TRACE_LOGS_VIEW_CONFIG.defaultColumns).not.toContain(
      COLUMN_EXPERIMENT_ID,
    );
  });
});
