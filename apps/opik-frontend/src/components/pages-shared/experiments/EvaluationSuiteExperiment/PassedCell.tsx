import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import BehaviorsBreakdownTooltip from "./BehaviorsBreakdownTooltip";
import { ExperimentsCompare } from "@/types/datasets";
import {
  ExperimentItemStatus,
  BehaviorResult,
} from "@/types/evaluation-suites";

interface ExperimentItemWithStatus {
  status?: ExperimentItemStatus;
  behavior_results?: BehaviorResult[];
}

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

function getStatusFromExperimentItems(row: ExperimentsCompare): {
  status: ExperimentItemStatus | undefined;
  behaviorsByRun: BehaviorResult[][];
  passedCount: number;
  totalCount: number;
} {
  const items = row.experiment_items as unknown as ExperimentItemWithStatus[];
  if (!items?.length) {
    return {
      status: undefined,
      behaviorsByRun: [],
      passedCount: 0,
      totalCount: 0,
    };
  }

  const behaviorsByRun = items.map((item) => item.behavior_results ?? []);
  const passedCount = items.filter(
    (item) => item.status === ExperimentItemStatus.PASSED,
  ).length;

  return {
    status: items[0].status,
    behaviorsByRun,
    passedCount,
    totalCount: items.length,
  };
}

const PassedCell = (context: CellContext<ExperimentsCompare, unknown>) => {
  const row = context.row.original;
  const { status, behaviorsByRun, passedCount, totalCount } =
    getStatusFromExperimentItems(row);

  const isMultiRun = totalCount > 1;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {status ? (
        <BehaviorsBreakdownTooltip behaviorsByRun={behaviorsByRun}>
          <span
            className={`comet-body-s-accented cursor-default ${STATUS_DISPLAY[status].color}`}
          >
            {STATUS_DISPLAY[status].label}
            {isMultiRun && ` (${passedCount}/${totalCount})`}
          </span>
        </BehaviorsBreakdownTooltip>
      ) : (
        <span className="text-muted-slate">{"\u2014"}</span>
      )}
    </CellWrapper>
  );
};

export default PassedCell;
