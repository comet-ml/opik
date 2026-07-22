import React from "react";
import { CellContext } from "@tanstack/react-table";
import { ExperimentItem } from "@/types/datasets";
import { RunStatus } from "@/types/test-suites";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { StatusTag } from "@/v2/pages-shared/experiments/TestSuiteExperiment/PassedCell";

type FlattenedTrialItem = {
  experimentItem: ExperimentItem;
  allRuns: ExperimentItem[];
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

// Reuses the experiment page's StatusTag (colored Passed/Failed tag + assertions
// breakdown tooltip), building its StatusInfo from the flattened per-experiment
// row the same way getStatusInfoForExperiment does for the compare view.
const TrialPassedCell: React.FC<CellContext<FlattenedTrialItem, unknown>> = (
  context,
) => {
  const { allRuns, runSummary, experimentItem, executionPolicy } =
    context.row.original;
  const status = runSummary?.status ?? experimentItem.status;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <StatusTag
        status={status}
        evaluating={!status}
        assertionsByRun={allRuns.map((run) => run.assertion_results ?? [])}
        reason={undefined}
        passThreshold={executionPolicy?.pass_threshold}
        runsPerItem={executionPolicy?.runs_per_item}
      />
    </CellWrapper>
  );
};

export default TrialPassedCell;
