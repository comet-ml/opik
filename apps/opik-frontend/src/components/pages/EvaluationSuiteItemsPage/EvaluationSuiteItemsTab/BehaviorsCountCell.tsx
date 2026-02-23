import { CellContext, ColumnMeta, TableMeta } from "@tanstack/react-table";
import { DatasetItem, Evaluator } from "@/types/datasets";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  useItemAddedBehaviors,
  useItemEditedBehaviors,
  useItemDeletedBehaviorIds,
} from "@/store/EvaluationSuiteDraftStore";
import { useEvaluatorDisplayRows } from "@/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/useEvaluatorDisplayRows";
import {
  getMetricIcon,
  formatEvaluatorConfig,
  getSectionLabel,
} from "@/lib/evaluator-converters";
import {
  BehaviorDisplayRow,
  MetricType,
  LLMJudgeConfig,
} from "@/types/evaluation-suites";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const EMPTY_EVALUATORS: Evaluator[] = [];
const MAX_VISIBLE_ASSERTIONS = 3;

function EvaluatorTooltipEntry({ row }: { row: BehaviorDisplayRow }) {
  const Icon = getMetricIcon(row.type);
  const isLLMJudge = row.type === MetricType.LLM_AS_JUDGE;
  const assertions = isLLMJudge
    ? (row.config as LLMJudgeConfig).assertions ?? []
    : [];
  const configSummary = isLLMJudge
    ? null
    : formatEvaluatorConfig(row.type, row.config);

  const visible = assertions.slice(0, MAX_VISIBLE_ASSERTIONS);
  const hiddenCount = assertions.length - MAX_VISIBLE_ASSERTIONS;

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-1.5">
        <div className="flex size-5 shrink-0 items-center justify-center rounded bg-foreground/5">
          <Icon className="size-3 text-muted-slate" />
        </div>
        <span className="comet-body-xs-accented truncate">{row.name}</span>
      </div>
      <div className="flex flex-col gap-0.5 pl-[26px]">
        <span className="text-[9px] font-medium tracking-wider text-muted-slate">
          {getSectionLabel(row.type)}
        </span>
        {isLLMJudge ? (
          <>
            {visible.map((assertion, i) => (
              <div
                key={i}
                className="rounded bg-foreground/[0.03] px-1.5 py-0.5"
              >
                <span className="line-clamp-1 text-[11px] text-foreground">
                  {assertion}
                </span>
              </div>
            ))}
            {hiddenCount > 0 && (
              <span className="text-[11px] text-muted-slate">
                +{hiddenCount} more
              </span>
            )}
          </>
        ) : (
          configSummary && (
            <div className="rounded bg-foreground/[0.03] px-1.5 py-0.5">
              <span className="text-[11px] text-foreground">
                {configSummary}
              </span>
            </div>
          )
        )}
      </div>
    </div>
  );
}

interface BehaviorsCountCellInnerProps {
  itemId: string;
  item: DatasetItem;
  metadata: ColumnMeta<DatasetItem, unknown> | undefined;
  tableMetadata: TableMeta<DatasetItem> | undefined;
}

function BehaviorsCountCellInner({
  itemId,
  item,
  metadata,
  tableMetadata,
}: BehaviorsCountCellInnerProps) {
  const addedBehaviors = useItemAddedBehaviors(itemId);
  const editedBehaviors = useItemEditedBehaviors(itemId);
  const deletedBehaviorIds = useItemDeletedBehaviorIds(itemId);

  const displayRows = useEvaluatorDisplayRows(
    item.evaluators ?? EMPTY_EVALUATORS,
    addedBehaviors,
    editedBehaviors,
    deletedBehaviorIds,
  );

  const count = displayRows.length;

  if (count === 0) {
    return (
      <CellWrapper metadata={metadata} tableMetadata={tableMetadata}>
        <span className="text-muted-slate">&mdash;</span>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper
      metadata={metadata}
      tableMetadata={tableMetadata}
      className="p-0"
    >
      <Tooltip>
        <TooltipTrigger asChild>
          <div className="group flex size-full cursor-pointer items-center px-3 py-2">
            <span className="comet-body-s-accented text-foreground group-hover:underline">
              {count}
            </span>
          </div>
        </TooltipTrigger>
        <TooltipPortal>
          <TooltipContent
            side="bottom"
            align="start"
            collisionPadding={16}
            className="min-w-[200px] p-2"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex flex-col gap-2">
              {displayRows.map((row) => (
                <EvaluatorTooltipEntry key={row.id} row={row} />
              ))}
            </div>
          </TooltipContent>
        </TooltipPortal>
      </Tooltip>
    </CellWrapper>
  );
}

export function BehaviorsCountCell(
  context: CellContext<DatasetItem, unknown>,
): React.ReactElement {
  const item = context.row.original;

  return (
    <BehaviorsCountCellInner
      itemId={item.id}
      item={item}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    />
  );
}
