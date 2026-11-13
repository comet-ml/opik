import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { CellContext } from "@tanstack/react-table";
import useAutomationsList from "@/api/automations/useAutomationsList";
import useAutomationUpdateMutation from "@/api/automations/useAutomationUpdateMutation";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Switch } from "@/ui/switch";
import { Tag } from "@/ui/tag";
import useAppStore from "@/store/AppStore";
import { Automation } from "@/types/event-automations";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";
import { describeTrigger } from "./helpers";

type Cell = CellContext<Automation, unknown>;

const Wrap: React.FC<{
  context: Cell;
  className?: string;
  children: React.ReactNode;
}> = ({ context, className, children }) => (
  <CellWrapper
    metadata={context.column.columnDef.meta}
    tableMetadata={context.table.options.meta}
    className={className}
  >
    {children}
  </CellWrapper>
);

const RecipeCell = (context: Cell) => {
  const recipe = context.row.original.recipe;
  return (
    <Wrap context={context}>
      <Tag variant={recipe ? "primary" : "gray"} size="sm">
        {recipe ? `${recipe.id} v${recipe.version}` : "manual"}
      </Tag>
    </Wrap>
  );
};

const TriggersCell = (context: Cell) => {
  const lines = context.row.original.triggers.map((trigger) =>
    [trigger.event, describeTrigger(trigger)].filter(Boolean).join(" · "),
  );
  return (
    <Wrap context={context}>
      <TooltipWrapper content={lines.join(" | ")}>
        <span className="truncate">{lines.join(" or ")}</span>
      </TooltipWrapper>
    </Wrap>
  );
};

const StepsCell = (context: Cell) => (
  <Wrap context={context}>
    <div className="flex min-w-0 gap-1 overflow-hidden">
      {context.row.original.steps.map((step) => (
        <TooltipWrapper key={step.id} content={step.id}>
          <Tag variant="gray" size="sm" className="shrink-0">
            {step.type}
          </Tag>
        </TooltipWrapper>
      ))}
    </div>
  </Wrap>
);

const EnabledCell = (context: Cell) => {
  const automation = context.row.original;
  const update = useAutomationUpdateMutation();
  return (
    <Wrap context={context}>
      <Switch
        size="xs"
        checked={automation.enabled}
        disabled={update.isPending}
        onClick={(event) => event.stopPropagation()}
        onCheckedChange={(enabled) =>
          update.mutate({ automationId: automation.id, update: { enabled } })
        }
      />
    </Wrap>
  );
};

const PausedCell = (context: Cell) => {
  const paused = context.row.original.paused_reason;
  return (
    <Wrap context={context}>
      {paused ? (
        <Tag variant="orange" size="sm">
          {paused.limit} until {formatDate(paused.until)}
        </Tag>
      ) : (
        <span className="text-muted-slate">–</span>
      )}
    </Wrap>
  );
};

const StatsCell = (context: Cell) => {
  const stats = context.row.original.stats;
  if (!stats) {
    return (
      <Wrap context={context}>
        <span className="text-muted-slate">–</span>
      </Wrap>
    );
  }
  const tooltip = `Last ${stats.window_days} days: fired ${stats.fired}, matched ${stats.matched}, sampled ${stats.sampled}, steps done ${stats.steps_done}, skipped ${stats.steps_skipped}, failed ${stats.steps_failed}, items written ${stats.items_written}, cost $${stats.cost_usd}`;
  return (
    <Wrap context={context}>
      <TooltipWrapper content={tooltip}>
        <span className="truncate">
          {stats.fired} fired · {stats.matched} matched · {stats.items_written}{" "}
          written
          {stats.steps_failed > 0 && (
            <span className="text-destructive">
              {" "}
              · {stats.steps_failed} failed
            </span>
          )}
          {stats.cost_usd > 0 && ` · $${stats.cost_usd}`}
        </span>
      </TooltipWrapper>
    </Wrap>
  );
};

const CreatedViaCell = (context: Cell) => {
  const via = context.row.original.created_via;
  return (
    <Wrap context={context}>
      {via ? (
        <Tag variant="gray" size="sm">
          {via}
        </Tag>
      ) : (
        <span className="text-muted-slate">–</span>
      )}
    </Wrap>
  );
};

const COLUMNS: ColumnData<Automation>[] = [
  { id: COLUMN_NAME_ID, label: "Name", type: COLUMN_TYPE.string, size: 220 },
  {
    id: "recipe",
    label: "Recipe",
    type: COLUMN_TYPE.string,
    cell: RecipeCell as never,
    size: 120,
  },
  {
    id: "triggers",
    label: "Triggers",
    type: COLUMN_TYPE.string,
    cell: TriggersCell as never,
    size: 280,
  },
  {
    id: "steps",
    label: "Steps",
    type: COLUMN_TYPE.string,
    cell: StepsCell as never,
    size: 240,
  },
  {
    id: "enabled",
    label: "Enabled",
    type: COLUMN_TYPE.string,
    cell: EnabledCell as never,
    size: 90,
  },
  {
    id: "paused_reason",
    label: "Paused",
    type: COLUMN_TYPE.string,
    cell: PausedCell as never,
    size: 220,
  },
  {
    id: "stats",
    label: "Last 7 days",
    type: COLUMN_TYPE.string,
    cell: StatsCell as never,
    size: 280,
  },
  {
    id: "created_via",
    label: "Created via",
    type: COLUMN_TYPE.string,
    cell: CreatedViaCell as never,
    size: 100,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
];

const getRowId = (automation: Automation) => automation.id;

type AutomationsTableProps = {
  projectId: string;
};

const AutomationsTable: React.FC<AutomationsTableProps> = ({ projectId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const { data, isPending, isPlaceholderData, isFetching } = useAutomationsList(
    { projectId, page, size },
    { placeholderData: keepPreviousData, refetchInterval: 30000 },
  );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const columns = useMemo(
    () => convertColumnDataToColumn<Automation, Automation>(COLUMNS, {}),
    [],
  );

  const handleRowClick = useCallback(
    (automation: Automation) => {
      navigate({
        to: "/$workspaceName/projects/$projectId/automations/$automationId",
        params: { workspaceName, projectId, automationId: automation.id },
      });
    },
    [navigate, workspaceName, projectId],
  );

  const isTableLoading = isPending || (isPlaceholderData && rows.length === 0);

  return (
    <section>
      <h2 className="comet-body-accented">Automations</h2>
      <p className="comet-body-s mb-3 text-muted-slate">
        Every automation on this project. Click one for its runs.
      </p>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        getRowId={getRowId}
        noData={
          <DataTableNoData title="No automations yet. Enable a workflow above, or create one through the Opik MCP." />
        }
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
      />
      <div className="py-4">
        <DataTablePagination
          page={page}
          pageChange={setPage}
          size={size}
          sizeChange={setSize}
          total={data?.total ?? 0}
        />
      </div>
    </section>
  );
};

export default AutomationsTable;
