import React from "react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/shared/DataTableCells/VerticallySplitCellWrapper";
import AssertionsBreakdownTooltip from "./AssertionsBreakdownTooltip";
import { Tag, TagProps } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import {
  AssertionResult,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";
import { RunStatus } from "@/types/test-suites";
import { isAggregatedItem } from "@/lib/trials";

const STATUS_DISPLAY: Record<
  RunStatus,
  { label: string; variant: TagProps["variant"] }
> = {
  [RunStatus.PASSED]: { label: "Passed", variant: "green" },
  [RunStatus.FAILED]: { label: "Failed", variant: "pink" },
  [RunStatus.SKIPPED]: { label: "Skipped", variant: "gray" },
};

type StatusInfo = {
  status: RunStatus | undefined;
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
    (item) => item.status === RunStatus.PASSED,
  ).length;

  const hasEvaluators = (row.evaluators?.length ?? 0) > 0;
  const summaryValues = Object.values(row.run_summaries_by_experiment ?? {});
  let status: RunStatus | undefined;

  if (summaryValues.length > 0) {
    const allPassed = summaryValues.every((s) => s.status === RunStatus.PASSED);
    status = allPassed ? RunStatus.PASSED : RunStatus.FAILED;
  } else {
    status = items[0].status;
  }

  if (status === RunStatus.SKIPPED && hasEvaluators) {
    status = undefined;
  }

  return {
    status,
    assertionsByRun,
    passedCount,
    totalCount: items.length,
  };
}

function getStatusInfoForExperiment(
  row: ExperimentsCompare,
  experimentId: string,
  item: ExperimentItem | undefined,
): StatusInfo {
  const expItems: ExperimentItem[] = item
    ? isAggregatedItem(item)
      ? item.trialItems
      : [item]
    : [];

  if (!expItems.length) {
    return {
      status: undefined,
      assertionsByRun: [],
      passedCount: 0,
      totalCount: 0,
    };
  }

  const assertionsByRun = expItems.map((item) => item.assertion_results ?? []);
  const passedCount = expItems.filter(
    (item) => item.status === RunStatus.PASSED,
  ).length;

  const hasEvaluators = (row.evaluators?.length ?? 0) > 0;
  const summary = row.run_summaries_by_experiment?.[experimentId];
  let status: RunStatus | undefined;

  if (summary) {
    status = summary.status;
  } else {
    status = expItems[0].status;
  }

  if (status === RunStatus.SKIPPED && hasEvaluators) {
    status = undefined;
  }

  return {
    status,
    assertionsByRun,
    passedCount,
    totalCount: expItems.length,
  };
}

const StatusTag: React.FC<StatusInfo & { tagSize?: TagProps["size"] }> = ({
  status,
  assertionsByRun,
  passedCount,
  totalCount,
  tagSize = "md",
}) => {
  const isMultiRun = totalCount > 1;

  if (!status) {
    return <span className="text-muted-slate">{"\u2014"}</span>;
  }

  return (
    <AssertionsBreakdownTooltip assertionsByRun={assertionsByRun}>
      <Tag
        variant={STATUS_DISPLAY[status].variant}
        size={tagSize}
        className="cursor-default"
      >
        {STATUS_DISPLAY[status].label}
        {isMultiRun && ` (${passedCount}/${totalCount})`}
      </Tag>
    </AssertionsBreakdownTooltip>
  );
};

const PassedCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { experimentsIds } = (custom ?? {}) as Partial<CustomMeta>;
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  if (experimentsIds) {
    const renderContent = (
      item: ExperimentItem | undefined,
      experimentId: string,
    ) => {
      const statusInfo = getStatusInfoForExperiment(row, experimentId, item);
      return <StatusTag {...statusInfo} tagSize={tagSize} />;
    };

    return (
      <VerticallySplitCellWrapper
        renderContent={renderContent}
        experimentCompare={row}
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
        rowId={context.row.id}
      />
    );
  }

  const statusInfo = getStatusFromExperimentItems(row);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <StatusTag {...statusInfo} tagSize={tagSize} />
    </CellWrapper>
  );
};

export default PassedCell;
