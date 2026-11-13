import React, { useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { CellContext } from "@tanstack/react-table";
import useAutomationById from "@/api/automations/useAutomationById";
import useAutomationRunsList from "@/api/automations/useAutomationRunsList";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import CodeCell from "@/shared/DataTableCells/CodeCell";
import TextCell from "@/shared/DataTableCells/TextCell";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { Tag, TagProps } from "@/ui/tag";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import {
  AutomationRun,
  AutomationRunStatus,
  AutomationRunStep,
} from "@/types/event-automations";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";
import RunDetailsDialog from "./RunDetailsDialog";
import { describeTrigger } from "./helpers";

type Cell = CellContext<AutomationRun, unknown>;

const STATUS_VARIANT: Record<AutomationRunStatus, TagProps["variant"]> = {
  pending: "yellow",
  done: "green",
  partial: "orange",
  failed: "red",
};

const Wrap: React.FC<{ context: Cell; children: React.ReactNode }> = ({
  context,
  children,
}) => (
  <CellWrapper
    metadata={context.column.columnDef.meta}
    tableMetadata={context.table.options.meta}
  >
    {children}
  </CellWrapper>
);

const KindCell = (context: Cell) => (
  <Wrap context={context}>
    <Tag variant="gray" size="sm">
      {context.row.original.kind}
    </Tag>
  </Wrap>
);

const StatusCell = (context: Cell) => {
  const run = context.row.original;
  return (
    <Wrap context={context}>
      <TooltipWrapper
        content={run.waiting_on ? `Waiting on ${run.waiting_on}` : undefined}
      >
        <Tag variant={STATUS_VARIANT[run.status] ?? "gray"} size="sm">
          {run.status}
          {run.waiting_on ? " · waiting" : ""}
        </Tag>
      </TooltipWrapper>
    </Wrap>
  );
};

const TriggerCell = (context: Cell) => {
  const run = context.row.original;
  const trigger = run.trigger;
  if (!trigger) {
    return (
      <Wrap context={context}>
        <span className="text-muted-slate">–</span>
      </Wrap>
    );
  }
  const counts =
    run.kind === "rollup"
      ? ` · ${trigger.fired} fired, ${trigger.matched} matched, ${trigger.sampled} sampled`
      : "";
  return (
    <Wrap context={context}>
      <span className="truncate">
        {trigger.event}
        {counts}
      </span>
    </Wrap>
  );
};

const range = (from?: string | null, to?: string | null) =>
  `${from ? formatDate(from) : "…"} → ${to ? formatDate(to) : "…"}`;

const WhenCell = (context: Cell) => {
  const run = context.row.original;
  const text =
    run.kind === "rollup"
      ? range(run.window_start, run.window_end)
      : range(run.started_at, run.finished_at);
  return (
    <Wrap context={context}>
      <TooltipWrapper content={text}>
        <span className="truncate">{text}</span>
      </TooltipWrapper>
    </Wrap>
  );
};

const stepVariant = (step: AutomationRunStep): TagProps["variant"] => {
  if (step.status === "failed" || step.failed > 0) return "red";
  if (step.status === "done" || step.done > 0) return "green";
  return "gray";
};

const stepLabel = (run: AutomationRun, step: AutomationRunStep) =>
  run.kind === "single"
    ? `${step.id}: ${step.status ?? "–"}`
    : `${step.id}: ${step.done}/${step.skipped}/${step.failed}`;

const stepTooltip = (run: AutomationRun, step: AutomationRunStep) =>
  [
    step.type,
    run.kind === "rollup" ? "done / skipped / failed" : step.reason,
    step.last_error ? `Last error: ${step.last_error}` : null,
  ]
    .filter(Boolean)
    .join(" · ");

const StepsCell = (context: Cell) => {
  const run = context.row.original;
  return (
    <Wrap context={context}>
      <div className="flex min-w-0 gap-1 overflow-hidden">
        {run.steps.map((step) => (
          <TooltipWrapper key={step.id} content={stepTooltip(run, step)}>
            <Tag variant={stepVariant(step)} size="sm" className="shrink-0">
              {stepLabel(run, step)}
            </Tag>
          </TooltipWrapper>
        ))}
      </div>
    </Wrap>
  );
};

type ParentCellMeta = {
  onOpenRun: (runId: string) => void;
};

const ParentCell = (context: Cell) => {
  const parentRunId = context.row.original.parent_run_id;
  const { onOpenRun } = (context.column.columnDef.meta?.custom ??
    {}) as Partial<ParentCellMeta>;
  return (
    <Wrap context={context}>
      {parentRunId ? (
        <Button
          variant="link"
          size="2xs"
          className="px-0"
          onClick={(event) => {
            event.stopPropagation();
            onOpenRun?.(parentRunId);
          }}
        >
          {parentRunId.slice(0, 8)}…
        </Button>
      ) : (
        <span className="text-muted-slate">–</span>
      )}
    </Wrap>
  );
};

const getRowId = (run: AutomationRun) => run.id;

const AutomationRunsPage: React.FC = () => {
  const { automationId = "" } = useParams({ strict: false });
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [openRunId, setOpenRunId] = useState<string | null>(null);

  const { data: automation } = useAutomationById(
    { automationId },
    { enabled: Boolean(automationId) },
  );

  useEffect(() => {
    if (automation?.name) {
      setBreadcrumbParam("automationId", automationId, automation.name);
    }
  }, [automation?.name, automationId, setBreadcrumbParam]);

  const { data, isPending, isPlaceholderData, isFetching } =
    useAutomationRunsList(
      { automationId, page, size },
      {
        placeholderData: keepPreviousData,
        refetchInterval: 30000,
        enabled: Boolean(automationId),
      },
    );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);

  const columns = useMemo(() => {
    const definitions: ColumnData<AutomationRun>[] = [
      {
        id: "kind",
        label: "Kind",
        type: COLUMN_TYPE.string,
        cell: KindCell as never,
        size: 90,
      },
      {
        id: "status",
        label: "Status",
        type: COLUMN_TYPE.string,
        cell: StatusCell as never,
        size: 130,
      },
      {
        id: "trigger",
        label: "Trigger",
        type: COLUMN_TYPE.string,
        cell: TriggerCell as never,
        size: 300,
      },
      {
        id: "item_id",
        label: "Item",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => row.item_id ?? "",
        cell: TextCell as never,
        size: 180,
      },
      {
        id: "when",
        label: "When",
        type: COLUMN_TYPE.string,
        cell: WhenCell as never,
        size: 300,
      },
      {
        id: "steps",
        label: "Steps",
        type: COLUMN_TYPE.string,
        cell: StepsCell as never,
        size: 300,
      },
      {
        id: "outputs",
        label: "Outputs",
        type: COLUMN_TYPE.dictionary,
        accessorFn: (row) => (row.outputs ? JSON.stringify(row.outputs) : ""),
        cell: CodeCell as never,
        size: 260,
      },
      {
        id: "last_error",
        label: "Last error",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => row.last_error ?? "",
        cell: TextCell as never,
        size: 240,
      },
      {
        id: "parent_run_id",
        label: "Parent run",
        type: COLUMN_TYPE.string,
        cell: ParentCell as never,
        customMeta: { onOpenRun: setOpenRunId } satisfies ParentCellMeta,
        size: 120,
      },
    ];
    return convertColumnDataToColumn<AutomationRun, AutomationRun>(
      definitions,
      {},
    );
  }, []);

  const isTableLoading = isPending || (isPlaceholderData && rows.length === 0);
  const triggers = (automation?.triggers ?? [])
    .map((trigger) =>
      [trigger.event, describeTrigger(trigger)].filter(Boolean).join(" · "),
    )
    .join(" or ");

  return (
    <div className="flex min-h-full flex-col pt-4">
      <div className="mb-1 flex min-h-7 items-center justify-between gap-4">
        <h1 className="comet-body-accented truncate">
          {automation?.name ?? "Runs"}
        </h1>
        {automation && (
          <Tag variant={automation.enabled ? "green" : "gray"}>
            {automation.enabled ? "Enabled" : "Disabled"}
          </Tag>
        )}
      </div>
      <p className="comet-body-s mb-4 text-muted-slate">
        {automation
          ? `${
              automation.recipe
                ? `${automation.recipe.id} v${automation.recipe.version} · `
                : ""
            }${triggers} → ${automation.steps
              .map((step) => step.type)
              .join(" → ")}`
          : "Every run of this automation, newest first. Click a run for its full record."}
      </p>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={(run) => setOpenRunId(run.id)}
        getRowId={getRowId}
        noData={<DataTableNoData title="No runs yet." />}
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
      {openRunId && (
        <RunDetailsDialog
          key={openRunId}
          runId={openRunId}
          open
          setOpen={(open) => !open && setOpenRunId(null)}
        />
      )}
    </div>
  );
};

export default AutomationRunsPage;
