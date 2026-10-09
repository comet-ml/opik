import { describe, it, expect } from "vitest";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AGENT_INSIGHTS_JOB_STATUS,
  AgentInsightsIssue,
  AgentInsightsJob,
} from "@/types/signals";
import {
  countAffectedTraces,
  getHeaderControls,
  isGuidanceOutdated,
} from "./helpers";

const job = (fields: Partial<AgentInsightsJob>): AgentInsightsJob => ({
  id: "j1",
  project_id: "p1",
  status: AGENT_INSIGHTS_JOB_STATUS.disabled,
  ...fields,
});

const issue = (
  status: AGENT_INSIGHTS_ISSUE_STATUS,
  total_occurrences: number,
): AgentInsightsIssue => ({
  id: `${status}-${total_occurrences}`,
  name: "issue",
  status,
  total_occurrences,
  latest_count: 0,
  total: 0,
  users_impacted: 0,
  total_users: 0,
  days_reported: 1,
});

describe("isGuidanceOutdated", () => {
  const scanned = { last_scan_at: "2026-10-08T14:00:00Z" };

  it("is true when guidance changed after the results", () => {
    expect(
      isGuidanceOutdated(
        job({ ...scanned, guidance_version: 2, results_guidance_version: 1 }),
      ),
    ).toBe(true);
  });

  it("treats missing results version as 0", () => {
    expect(isGuidanceOutdated(job({ ...scanned, guidance_version: 1 }))).toBe(
      true,
    );
    expect(isGuidanceOutdated(job({ ...scanned }))).toBe(false);
  });

  it("is false when the results match the guidance", () => {
    expect(
      isGuidanceOutdated(
        job({ ...scanned, guidance_version: 1, results_guidance_version: 1 }),
      ),
    ).toBe(false);
  });

  it("is false before the first run", () => {
    expect(isGuidanceOutdated(job({ guidance_version: 1 }))).toBe(false);
    expect(isGuidanceOutdated(null)).toBe(false);
  });
});

describe("countAffectedTraces", () => {
  it("leaves out issues closed as not useful", () => {
    expect(
      countAffectedTraces([
        issue(AGENT_INSIGHTS_ISSUE_STATUS.open, 10),
        issue(AGENT_INSIGHTS_ISSUE_STATUS.resolved, 5),
        issue(AGENT_INSIGHTS_ISSUE_STATUS.closed, 20),
      ]),
    ).toBe(15);
  });
});

describe("getHeaderControls", () => {
  it("offers Settings before the first run, but not Run", () => {
    expect(
      getHeaderControls({
        showClosed: false,
        canConfigure: true,
        showJobControls: false,
      }),
    ).toEqual({ settings: true, run: false });
  });

  it("offers both once the job has something to show", () => {
    expect(
      getHeaderControls({
        showClosed: false,
        canConfigure: true,
        showJobControls: true,
      }),
    ).toEqual({ settings: true, run: true });
  });

  it("offers neither without configure permission or on Closed issues", () => {
    const none = { settings: false, run: false };
    expect(
      getHeaderControls({
        showClosed: false,
        canConfigure: false,
        showJobControls: true,
      }),
    ).toEqual(none);
    expect(
      getHeaderControls({
        showClosed: true,
        canConfigure: true,
        showJobControls: true,
      }),
    ).toEqual(none);
  });
});
