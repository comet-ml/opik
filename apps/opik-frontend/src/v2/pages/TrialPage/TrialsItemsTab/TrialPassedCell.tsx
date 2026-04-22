import React from "react";
import { CellContext } from "@tanstack/react-table";
import { RunStatus } from "@/types/datasets";

type FlattenedTrialItem = {
  experimentItem: {
    status?: RunStatus;
  };
  allRuns: {
    status?: RunStatus;
  }[];
  runSummary?: {
    passed_runs: number;
    total_runs: number;
    status: RunStatus;
  };
  executionPolicy?: {
    runs_per_item?: number;
    pass_threshold?: number;
  };
};

const TrialPassedCell: React.FC<CellContext<FlattenedTrialItem, unknown>> = (
  context,
) => {
  const row = context.row.original;
  const { allRuns, runSummary, executionPolicy } = row;

  if (runSummary) {
    const { passed_runs, total_runs, status } = runSummary;
    const itemPassed = status === "passed";

    if (total_runs === 1) {
      return (
        <span className={itemPassed ? "text-success" : "text-destructive"}>
          {itemPassed ? "Yes" : "No"}
        </span>
      );
    }

    const passThreshold = executionPolicy?.pass_threshold ?? 1;
    return (
      <span className={itemPassed ? "text-success" : "text-destructive"}>
        {passed_runs}/{total_runs} (threshold: {passThreshold})
      </span>
    );
  }

  const firstRun = row.experimentItem;
  if (!firstRun.status) {
    return <span>-</span>;
  }

  if (allRuns.length === 1) {
    const itemPassed = firstRun.status === "passed";
    return (
      <span className={itemPassed ? "text-success" : "text-destructive"}>
        {itemPassed ? "Yes" : "No"}
      </span>
    );
  }

  const runsPassed = allRuns.filter((r) => r.status === "passed").length;
  const passThreshold = executionPolicy?.pass_threshold ?? 1;
  const itemPassed = runsPassed >= passThreshold;

  return (
    <span className={itemPassed ? "text-success" : "text-destructive"}>
      {runsPassed}/{allRuns.length} (threshold: {passThreshold})
    </span>
  );
};

export default TrialPassedCell;
