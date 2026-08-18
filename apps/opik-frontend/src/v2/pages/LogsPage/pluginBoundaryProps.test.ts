/**
 * Compile guard for the two boundaries that plugin repos reach into.
 *
 * `useLogsType` and `LogsTab` are rendered from plugin repos (AiSpendSessionsPage) that are checked
 * out at build time and type-checked against whatever this repo's `main` holds. Making a prop
 * *required* on either shape therefore breaks a build we cannot see from here: every in-repo caller
 * gets updated in the same PR, so the OSS image stays green and the failure only appears later, in
 * the comet image, at release time. That is exactly how #7801 broke two release runs.
 *
 * These assertions are about types, not behaviour. `tsc` type-checks everything under `src`, so
 * requiring a prop on either shape again fails the OSS build here — in the PR that does it, rather
 * than in a release job days later. The runtime `expect`s only pin the fallback value.
 */
import type { ComponentProps } from "react";
import { describe, it, expect } from "vitest";
import { LOGS_TYPE } from "@/constants/traces";
import {
  DEFAULT_PROJECT_DATE_RANGE_CONFIG,
  resolveProjectDateRangeConfig,
} from "@/v2/pages-shared/traces/resolveProjectDateRangeConfig";
import { DEFAULT_DATE_PRESET } from "@/v2/pages-shared/traces/MetricDateRangeSelect/constants";
import LogsTab from "./LogsTab";
import useLogsType from "./useLogsType";

describe("LogsPage boundaries used by plugin repos", () => {
  it("should accept useLogsType options without a date range config", () => {
    // The exact call shape from AiSpendSessionsPage.
    const options: Parameters<typeof useLogsType>[0] = {
      projectId: "project-id",
    };

    expect(options.dateRangeConfig).toBeUndefined();
  });

  it("should accept LogsTab props without a date range config", () => {
    const props: ComponentProps<typeof LogsTab> = {
      projectId: "project-id",
      projectName: "project-name",
      logsType: LOGS_TYPE.traces,
      onLogsTypeChange: () => {},
    };

    expect(props.dateRangeConfig).toBeUndefined();
  });

  it("should fall back to what an ordinary project resolves to", () => {
    // Omitting the config has to mean "behave as before the demo override", not "no config".
    expect(DEFAULT_PROJECT_DATE_RANGE_CONFIG).toEqual(
      resolveProjectDateRangeConfig(undefined),
    );
    expect(DEFAULT_PROJECT_DATE_RANGE_CONFIG).toEqual({
      defaultValue: DEFAULT_DATE_PRESET,
      storageKeySuffix: "",
    });
  });
});
