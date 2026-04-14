import React from "react";
import { CircleCheck, CircleX } from "lucide-react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/shared/DataTableCells/VerticallySplitCellWrapper";
import AssertionsBreakdownTooltip from "./AssertionsBreakdownTooltip";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { cn } from "@/lib/utils";
import {
  AssertionResult,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";
import { ExperimentItemStatus } from "@/types/evaluation-suites";
import { isAggregatedItem } from "@/lib/trials";

type StatusInfo = {
  status: ExperimentItemStatus | undefined;
  assertionsByRun: AssertionResult[][];
  passedCount: number;
  totalCount: number;
  skippedReason?: string;
};

const NO_EXPERIMENT_ITEM_REASON = "No experiment item defined";
const NO_ASSERTIONS_REASON = "No assertions defined";

export function getStatusFromExperimentItems(
  row: ExperimentsCompare,
): StatusInfo {
  const items = row.experiment_items;
  if (!items?.length) {
    return {
      status: ExperimentItemStatus.SKIPPED,
      assertionsByRun: [],
      passedCount: 0,
      totalCount: 0,
      skippedReason: NO_EXPERIMENT_ITEM_REASON,
    };
  }

  const assertionsByRun = items.map((item) => item.assertion_results ?? []);
  const passedCount = items.filter(
    (item) => item.status === ExperimentItemStatus.PASSED,
  ).length;

  const summaryValues = Object.values(row.run_summaries_by_experiment ?? {});
  let status: ExperimentItemStatus | undefined;

  if (summaryValues.length > 0) {
    const allPassed = summaryValues.every(
      (s) => s.status === ExperimentItemStatus.PASSED,
    );
    status = allPassed
      ? ExperimentItemStatus.PASSED
      : ExperimentItemStatus.FAILED;
  } else {
    status = items[0].status;
  }

  const isSkipped = !status;

  return {
    status: status ?? ExperimentItemStatus.SKIPPED,
    assertionsByRun,
    passedCount,
    totalCount: row.execution_policy?.runs_per_item ?? items.length,
    skippedReason: isSkipped ? NO_ASSERTIONS_REASON : undefined,
  };
}

export function getStatusInfoForExperiment(
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
      status: ExperimentItemStatus.SKIPPED,
      assertionsByRun: [],
      passedCount: 0,
      totalCount: 0,
      skippedReason: NO_EXPERIMENT_ITEM_REASON,
    };
  }

  const assertionsByRun = expItems.map((item) => item.assertion_results ?? []);
  const passedCount = expItems.filter(
    (item) => item.status === ExperimentItemStatus.PASSED,
  ).length;

  const summary = row.run_summaries_by_experiment?.[experimentId];
  let status: ExperimentItemStatus | undefined;

  if (summary) {
    status = summary.status;
  } else {
    status = expItems[0].status;
  }

  const isSkipped = !status;

  return {
    status: status ?? ExperimentItemStatus.SKIPPED,
    assertionsByRun,
    passedCount: summary?.passed_runs ?? passedCount,
    // Fall back to 0 when no summary and no policy — status will be SKIPPED so count isn't rendered
    totalCount: summary?.total_runs ?? row.execution_policy?.runs_per_item ?? 0,
    skippedReason: isSkipped ? NO_ASSERTIONS_REASON : undefined,
  };
}

export const StatusTag: React.FC<StatusInfo & { className?: string }> = ({
  status,
  assertionsByRun,
  passedCount,
  totalCount,
  skippedReason,
  className,
}) => {
  if (!status) {
    return null;
  }

  const isSkipped = status === ExperimentItemStatus.SKIPPED;
  const isPassed = status === ExperimentItemStatus.PASSED;
  const Icon = isPassed ? CircleCheck : CircleX;

  const tag = (
    <span
      className={cn(
        "inline-flex h-5 items-center gap-1 rounded-md border border-transparent px-2 font-mono text-xs font-semibold transition-colors",
        isPassed
          ? "bg-[var(--tag-green-bg)] text-[var(--tag-green-text)]"
          : isSkipped
            ? "bg-muted text-muted-foreground"
            : "bg-[var(--tag-red-bg)] text-[var(--tag-red-text)]",
        "cursor-default",
        className,
      )}
    >
      {isSkipped ? (
        "Skipped"
      ) : (
        <>
          <Icon className="size-3 shrink-0" />
          {passedCount}/{totalCount}
        </>
      )}
    </span>
  );

  if (isSkipped) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{tag}</TooltipTrigger>
        {skippedReason && (
          <TooltipPortal>
            <TooltipContent side="bottom" collisionPadding={16}>
              {skippedReason}
            </TooltipContent>
          </TooltipPortal>
        )}
      </Tooltip>
    );
  }

  return (
    <AssertionsBreakdownTooltip assertionsByRun={assertionsByRun}>
      {tag}
    </AssertionsBreakdownTooltip>
  );
};

const PassedCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { experimentsIds } = (custom ?? {}) as Partial<CustomMeta>;
  if (experimentsIds) {
    const renderContent = (
      item: ExperimentItem | undefined,
      experimentId: string,
    ) => {
      const statusInfo = getStatusInfoForExperiment(row, experimentId, item);
      return <StatusTag {...statusInfo} />;
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
      <StatusTag {...statusInfo} />
    </CellWrapper>
  );
};

export default PassedCell;
