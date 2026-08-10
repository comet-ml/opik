import { describe, it, expect } from "vitest";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import {
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./MetricDateRangeSelect/constants";
import { resolveProjectDateRangeConfig } from "./resolveProjectDateRangeConfig";

describe("resolveProjectDateRangeConfig", () => {
  it("should default the demo project to 24 hours so its charts bucket hourly", () => {
    const result = resolveProjectDateRangeConfig(DEMO_PROJECT_NAME, true);

    expect(result.defaultValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);
  });

  it("should leave other projects on the workspace-wide default", () => {
    const result = resolveProjectDateRangeConfig("My Real Project", true);

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should not claim the demo default before the name is known", () => {
    const result = resolveProjectDateRangeConfig(undefined, false);

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
    expect(result.initSyncReady).toBe(false);
  });

  it("should not mistake the raw project id for a project name", () => {
    // LogsPage's own `projectName` falls back to the id while loading; callers must pass
    // project?.name instead, and an id must never read as the demo project.
    const result = resolveProjectDateRangeConfig(
      "019feaba-9c9b-71c3-93f5-905be65789c5",
      true,
    );

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
    expect(result.storageKeySuffix).toBe("");
  });

  // The stored range is sticky across projects and outranks any defaultValue, so without its own
  // storage slot the demo project would inherit whatever range was last picked on a real project
  // and the 24h default would never apply.
  describe("storageKeySuffix", () => {
    it("should give the demo project its own storage slot, named after the project", () => {
      const result = resolveProjectDateRangeConfig(DEMO_PROJECT_NAME, true);

      expect(result.storageKeySuffix).toBe(`-${DEMO_PROJECT_NAME}`);
    });

    it("should leave other projects on the shared storage slot", () => {
      const result = resolveProjectDateRangeConfig("My Real Project", true);

      expect(result.storageKeySuffix).toBe("");
    });

    it("should not claim the demo slot before the name resolves", () => {
      const result = resolveProjectDateRangeConfig(undefined, false);

      expect(result.storageKeySuffix).toBe("");
    });
  });

  describe("initSyncReady", () => {
    it("should report ready once the caller says the name is settled", () => {
      expect(
        resolveProjectDateRangeConfig(DEMO_PROJECT_NAME, true).initSyncReady,
      ).toBe(true);
    });

    it("should hold while the caller says the name is not settled", () => {
      expect(
        resolveProjectDateRangeConfig(undefined, false).initSyncReady,
      ).toBe(false);
    });

    it("should treat a settled-but-nameless lookup as ready", () => {
      // A failed project lookup: the override does not apply and the workspace default stands,
      // which beats never syncing the URL at all.
      const result = resolveProjectDateRangeConfig(undefined, true);

      expect(result.initSyncReady).toBe(true);
      expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
      expect(result.storageKeySuffix).toBe("");
    });
  });
});
