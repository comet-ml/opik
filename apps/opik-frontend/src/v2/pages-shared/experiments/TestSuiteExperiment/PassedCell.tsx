import React from "react";
import { CircleCheck, CircleX, Loader2 } from "lucide-react";
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
import { ExperimentItemStatus } from "@/types/test-suites";
import { isAggregatedItem } from "@/lib/trials";

type StatusInfo = {
  status: ExperimentItemStatus | undefined;
  evaluating: boolean;
  assertionsByRun: AssertionResult[][];
  skippedReason: string | undefined;
  passThreshold: number | undefined;
  runsPerItem: number | undefined;
};

const NO_EXPERIMENT_ITEM_REASON = "No experiment item defined";
const NO_ASSERTIONS_REASON = "No assertions defined";

const SKIPPED_RESULT = (reason: string): StatusInfo => ({
  status: ExperimentItemStatus.SKIPPED,
  evaluating: false,
  assertionsByRun: [],
  skippedReason: reason,
  passThreshold: undefined,
  runsPerItem: undefined,
});

export function getStatusFromExperimentItems(
  row: ExperimentsCompare,
): StatusInfo {
  const items = row.experiment_items;
  if (!items?.length) return SKIPPED_RESULT(NO_EXPERIMENT_ITEM_REASON);

  const hasEvaluators = (row.evaluators?.length ?? 0) > 0;

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

  if (!status && !hasEvaluators) return SKIPPED_RESULT(NO_ASSERTIONS_REASON);

  // Item-level execution_policy overrides the dataset-level one
  const passThreshold =
    items[0]?.execution_policy?.pass_threshold ??
    row.execution_policy?.pass_threshold;
  const runsPerItem =
    items[0]?.execution_policy?.runs_per_item ??
    row.execution_policy?.runs_per_item;

  return {
    status,
    evaluating: !status && hasEvaluators,
    assertionsByRun: items.map((item) => item.assertion_results ?? []),
    skippedReason: undefined,
    passThreshold,
    runsPerItem,
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

  if (!expItems.length) return SKIPPED_RESULT(NO_EXPERIMENT_ITEM_REASON);

  const hasEvaluators = (row.evaluators?.length ?? 0) > 0;
  const summary = row.run_summaries_by_experiment?.[experimentId];
  const status = summary ? summary.status : expItems[0].status;

  if (!status && !hasEvaluators) return SKIPPED_RESULT(NO_ASSERTIONS_REASON);

  // Item-level execution_policy overrides the dataset-level one
  const passThreshold =
    expItems[0]?.execution_policy?.pass_threshold ??
    row.execution_policy?.pass_threshold;
  const runsPerItem =
    expItems[0]?.execution_policy?.runs_per_item ??
    row.execution_policy?.runs_per_item;

  return {
    status,
    evaluating: !status && hasEvaluators,
    assertionsByRun: expItems.map((item) => item.assertion_results ?? []),
    skippedReason: undefined,
    passThreshold,
    runsPerItem,
  };
}

export const StatusTag: React.FC<StatusInfo & { className?: string }> = ({
  status,
  evaluating,
  assertionsByRun,
  skippedReason,
  passThreshold,
  runsPerItem,
  className,
}) => {
  if (evaluating) {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-muted-slate">
        <Loader2 className="size-3 animate-spin" />
        Evaluating assertions
      </span>
    );
  }

  if (!status) {
    return null;
  }

  const isSkipped = status === ExperimentItemStatus.SKIPPED;
  const isPassed = status === ExperimentItemStatus.PASSED;
  const Icon = isPassed ? CircleCheck : CircleX;

  const tag = (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-md border border-transparent px-1.5 py-0.5 text-sm font-medium transition-colors",
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
          {isPassed ? "Passed" : "Failed"}
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
    <AssertionsBreakdownTooltip
      assertionsByRun={assertionsByRun}
      passThreshold={passThreshold}
      runsPerItem={runsPerItem}
    >
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
      return (
        <div className="flex h-full items-center">
          <StatusTag {...statusInfo} />
        </div>
      );
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
