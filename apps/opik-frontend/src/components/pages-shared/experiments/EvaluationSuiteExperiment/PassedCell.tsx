import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import AssertionsBreakdownTooltip from "./AssertionsBreakdownTooltip";
import { AssertionResult, ExperimentsCompare } from "@/types/datasets";
import { ExperimentItemStatus } from "@/types/evaluation-suites";

const STATUS_DISPLAY: Record<
  ExperimentItemStatus,
  { label: string; color: string }
> = {
  [ExperimentItemStatus.PASSED]: { label: "Yes", color: "text-green-600" },
  [ExperimentItemStatus.FAILED]: { label: "No", color: "text-red-600" },
  [ExperimentItemStatus.SKIPPED]: {
    label: "Skipped",
    color: "text-muted-slate",
  },
};

type StatusInfo = {
  status: ExperimentItemStatus | undefined;
  assertionsByRun: AssertionResult[][];
  passedCount: number;
  totalCount: number;
};

function getStatusFromExperimentItems(row: ExperimentsCompare): StatusInfo {
  const items = row.experiment_items;
  if (!items?.length) {
    return {
      status: undefined,
      assertionsByRun: [],
      passedCount: 0,
      totalCount: 0,
    };
  }

  const assertionsByRun = items.map((item) => item.assertion_results ?? []);
  const passedCount = items.filter(
    (item) => item.status === ExperimentItemStatus.PASSED,
  ).length;

  return {
    status: items[0].status,
    assertionsByRun,
    passedCount,
    totalCount: items.length,
  };
}

const PassedCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const row = context.row.original;
  const { status, assertionsByRun, passedCount, totalCount } =
    getStatusFromExperimentItems(row);

  const isMultiRun = totalCount > 1;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {status ? (
        <AssertionsBreakdownTooltip assertionsByRun={assertionsByRun}>
          <span
            className={`comet-body-s-accented cursor-default ${STATUS_DISPLAY[status].color}`}
          >
            {STATUS_DISPLAY[status].label}
            {isMultiRun && ` (${passedCount}/${totalCount})`}
          </span>
        </AssertionsBreakdownTooltip>
      ) : (
        <span className="text-muted-slate">{"\u2014"}</span>
      )}
    </CellWrapper>
  );
};

export default PassedCell;
