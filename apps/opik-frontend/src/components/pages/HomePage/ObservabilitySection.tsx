import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import useProjectWithStatisticsList from "@/hooks/useProjectWithStatisticsList";
import Loader from "@/components/shared/Loader/Loader";
import AddEditProjectDialog from "@/components/pages/ProjectsPage/AddEditProjectDialog";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
} from "@/types/shared";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { ProjectWithStatistic } from "@/types/projects";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn } from "@/lib/table";
import FeedbackScoreListCell from "@/components/shared/DataTableCells/FeedbackScoreListCell";
import { get } from "lodash";
import ErrorsCountCell from "@/components/shared/DataTableCells/ErrorsCountCell";
import { LOGS_TYPE, PROJECT_TAB } from "@/constants/traces";

const COLUMNS_WIDTH_KEY = "home-projects-columns-width";

export const SHARED_COLUMNS = [
  {
    id: COLUMN_NAME_ID,
    label: "Project",
    type: COLUMN_TYPE.string,
    cell: ResourceCell as never,
    sortable: true,
    customMeta: {
      nameKey: "name",
      idKey: "id",
      resource: RESOURCE_TYPE.project,
    },
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row: ProjectWithStatistic) =>
      formatDate(row.last_updated_trace_at ?? row.last_updated_at),
    sortable: true,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row: ProjectWithStatistic) => get(row, "feedback_scores", []),
    cell: FeedbackScoreListCell as never,
    customMeta: {
      getHoverCardName: (row: ProjectWithStatistic) => row.name,
      areAggregatedScores: true,
    },
  },
  {
    id: "trace_count",
    label: "Trace count",
    type: COLUMN_TYPE.number,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const ObservabilitySection: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const { data, isPending } = useProjectWithStatisticsList(
    {
      workspaceName,
      sorting: [
        {
          id: "last_updated_trace_at",
          desc: true,
        },
      ],
      page: 1,
      size: 5,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const projects = useMemo(() => data?.content ?? [], [data?.content]);
  const noDataText = "There are no projects yet";

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const columnData = useMemo(() => {
    return convertColumnDataToColumn<
      ProjectWithStatistic,
      ProjectWithStatistic
    >(
      [
        ...SHARED_COLUMNS,
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
      ],
      {},
    );
  }, [navigate, workspaceName]);

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
      <h2 className="comet-title-s sticky top-0 z-10 truncate break-words bg-soft-background pb-3 pt-2">
        Observability
      </h2>
      <DataTable
        columns={columnData}
        data={projects}
        onRowClick={handleRowClick}
        resizeConfig={resizeConfig}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            <Button variant="link" onClick={handleNewProjectClick}>
              Create new project
            </Button>
          </DataTableNoData>
        }
      />
      <div className="flex justify-end pt-1">
        <Link to="/$workspaceName/projects" params={{ workspaceName }}>
          <Button variant="ghost" className="flex items-center gap-1 pr-0">
            All projects <ArrowRight className="size-4" />
          </Button>
        </Link>
      </div>
      <AddEditProjectDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default ObservabilitySection;
