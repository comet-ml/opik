import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import isNumber from "lodash/isNumber";
import get from "lodash/get";

import { formatNumericData } from "@/lib/utils";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import useProjectWithStatisticsList from "@/hooks/useProjectWithStatisticsList";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { ProjectWithStatistic } from "@/types/projects";
import Loader from "@/components/shared/Loader/Loader";
import AddEditProjectDialog from "@/components/pages/ProjectsPage/AddEditProjectDialog";
import ProjectsActionsPanel from "@/components/pages/ProjectsPage/ProjectsActionsPanel";
import { ProjectRowActionsCell } from "@/components/pages/ProjectsPage/ProjectRowActionsCell";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useAppStore from "@/store/AppStore";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { formatDate } from "@/lib/date";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_GUARDRAILS_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  HeaderIconType,
} from "@/types/shared";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState, ColumnSort } from "@tanstack/react-table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import FeedbackScoreListCell from "@/components/shared/DataTableCells/FeedbackScoreListCell";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import ErrorsCountCell from "@/components/shared/DataTableCells/ErrorsCountCell";
import { LOGS_TYPE, PROJECT_TAB } from "@/constants/traces";

export const getRowId = (p: ProjectWithStatistic) => p.id;

const SELECTED_COLUMNS_KEY = "projects-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "projects-columns-width";
const COLUMNS_ORDER_KEY = "projects-columns-order";
const COLUMNS_SORT_KEY = "projects-columns-sort";
const PAGINATION_SIZE_KEY = "projects-pagination-size";

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "last_updated_at",
  "trace_count",
  "duration.p50",
  "total_estimated_cost_sum",
  "error_count",
  "usage.total_tokens",
  COLUMN_FEEDBACK_SCORES_ID,
];

export const DEFAULT_SORTING_COLUMNS: ColumnSort[] = [
  {
    id: "last_updated_at",
    desc: true,
  },
];

const ProjectsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const columnsDef: ColumnData<ProjectWithStatistic>[] = useMemo(() => {
    return [
      {
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: TextCell as never,
        sortable: true,
      },
      {
        id: "last_updated_at",
        label: "Last updated",
        type: COLUMN_TYPE.time,
        accessorFn: (row) =>
          formatDate(row.last_updated_trace_at ?? row.last_updated_at),
        sortable: true,
      },
      {
        id: "trace_count",
        label: "Trace count",
        type: COLUMN_TYPE.number,
      },
      {
        id: "duration.p50",
        label: "Duration (avg.)",
        type: COLUMN_TYPE.duration,
        accessorFn: (row) => row.duration?.p50,
        cell: DurationCell as never,
      },
      {
        id: "total_estimated_cost_sum",
        label: "Total cost",
        type: COLUMN_TYPE.cost,
        cell: CostCell as never,
      },
      {
        id: "error_count",
        label: "Errors",
        type: COLUMN_TYPE.errors,
        cell: ErrorsCountCell as never,
        customMeta: {
          onZoomIn: (row: ProjectWithStatistic) => {
            navigate({
              to: "/$workspaceName/projects/$projectId/traces",
              params: {
                projectId: row.id,
                workspaceName,
              },
              search: {
                tab: PROJECT_TAB.logs,
                logsType: LOGS_TYPE.traces,
                traces_filters: [
                  {
                    operator: "is_not_empty",
                    type: COLUMN_TYPE.errors,
                    field: "error_info",
                    value: "",
                  },
                ],
              },
            });
          },
        },
      },
      {
        id: "usage.total_tokens",
        label: "Total tokens (avg.)",
        type: COLUMN_TYPE.number,
        accessorFn: (row) =>
          row.usage && isNumber(row.usage.total_tokens)
            ? formatNumericData(row.usage.total_tokens)
            : "-",
      },
      {
        id: COLUMN_FEEDBACK_SCORES_ID,
        label: "Feedback scores (avg.)",
        type: COLUMN_TYPE.numberDictionary,
        accessorFn: (row) => get(row, "feedback_scores", []),
        cell: FeedbackScoreListCell as never,
        customMeta: {
          getHoverCardName: (row: ProjectWithStatistic) => row.name,
          areAggregatedScores: true,
        },
        explainer: EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores],
      },
      {
        id: "id",
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        sortable: true,
      },
      {
        id: "thread_count",
        label: "Thread count",
        type: COLUMN_TYPE.number,
      },
      {
        id: "duration.p90",
        label: "Duration (p90)",
        type: COLUMN_TYPE.duration,
        accessorFn: (row) => row.duration?.p90,
        cell: DurationCell as never,
      },
      {
        id: "duration.p99",
        label: "Duration (p99)",
        type: COLUMN_TYPE.duration,
        accessorFn: (row) => row.duration?.p99,
        cell: DurationCell as never,
      },
      {
        id: "usage.prompt_tokens",
        label: "Input tokens (avg.)",
        type: COLUMN_TYPE.number,
        accessorFn: (row) =>
          row.usage && isNumber(row.usage.prompt_tokens)
            ? formatNumericData(row.usage.prompt_tokens)
            : "-",
      },
      {
        id: "usage.completion_tokens",
        label: "Output tokens (avg.)",
        type: COLUMN_TYPE.number,
        accessorFn: (row) =>
          row.usage && isNumber(row.usage.completion_tokens)
            ? formatNumericData(row.usage.completion_tokens)
            : "-",
      },
      ...(isGuardrailsEnabled
        ? [
            {
              id: COLUMN_GUARDRAILS_ID,
              label: "Guardrails",
              type: COLUMN_TYPE.category,
              iconType: "guardrails" as HeaderIconType,
              accessorFn: (row: ProjectWithStatistic) =>
                row.guardrails_failed_count &&
                isNumber(row.guardrails_failed_count)
                  ? `${row.guardrails_failed_count} failed`
                  : "-",
            },
          ]
        : []),
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        accessorFn: (row) => formatDate(row.created_at),
        sortable: true,
      },
      {
        id: "created_by",
        label: "Created by",
        type: COLUMN_TYPE.string,
      },
      {
        id: "description",
        label: "Description",
        type: COLUMN_TYPE.string,
      },
    ];
  }, [isGuardrailsEnabled, navigate, workspaceName]);

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });
  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [rowSelection = {}, setRowSelection] = useQueryParam(
    "selection",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING_COLUMNS,
    },
  );

  const { data, isPending, isPlaceholderData, isFetching } =
    useProjectWithStatisticsList(
      {
        workspaceName,
        search: search!,
        sorting: sortedColumns.map((column) => {
          if (column.id === "last_updated_at") {
            return {
              ...column,
              id: "last_updated_trace_at",
            };
          }
          return column;
        }),
        page: page!,
        size: size!,
      },
      {
        placeholderData: keepPreviousData,
      },
    );

  const projects = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noData = !search;
  const noDataText = noData ? "There are no projects yet" : "No search results";

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
        DEFAULT_SELECTED_COLUMNS,
        [COLUMN_NAME_ID],
      ),
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const selectedRows: ProjectWithStatistic[] = useMemo(() => {
    return projects.filter((row) => rowSelection[row.id]);
  }, [rowSelection, projects]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<ProjectWithStatistic>(),
      ...convertColumnDataToColumn<ProjectWithStatistic, ProjectWithStatistic>(
        columnsDef,
        {
          columnsOrder,
          selectedColumns,
        },
      ),
      generateActionsColumDef({
        cell: ProjectRowActionsCell,
      }),
    ];
  }, [selectedColumns, columnsOrder, columnsDef]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (row: ProjectWithStatistic) => {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: row.id,
          workspaceName,
        },
      });
    },
    [navigate, workspaceName],
  );

  const handleNewProjectClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Projects</h1>
      </div>
      <ExplainerDescription
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.what_do_you_use_projects_for]}
      />
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search!}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
          dimension="sm"
        ></SearchInput>
        <div className="flex items-center gap-2">
          <ProjectsActionsPanel projects={selectedRows} />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={columnsDef}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" size="sm" onClick={handleNewProjectClick}>
            Create new project
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={projects}
        onRowClick={handleRowClick}
        sortConfig={{
          enabled: true,
          sorting: sortedColumns,
          setSorting: setSortedColumns,
        }}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewProjectClick}>
                Create new project
              </Button>
            )}
          </DataTableNoData>
        }
        showLoadingOverlay={isPlaceholderData && isFetching}
      />
      <div className="py-4">
        <DataTablePagination
          page={page!}
          pageChange={setPage}
          size={size!}
          sizeChange={setSize}
          total={total}
        ></DataTablePagination>
      </div>
      <AddEditProjectDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default ProjectsPage;
