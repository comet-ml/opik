import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import useProjectWithStatisticsList from "@/hooks/useProjectWithStatisticsList";
import Loader from "@/components/shared/Loader/Loader";
import AddEditProjectDialog from "@/components/pages/ProjectsPage/AddEditProjectDialog";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { COLUMN_NAME_ID, COLUMN_SELECT_ID, COLUMN_TYPE } from "@/types/shared";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { ProjectWithStatistic } from "@/types/projects";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn } from "@/lib/table";

const COLUMNS_WIDTH_KEY = "home-projects-columns-width";

export const COLUMNS = convertColumnDataToColumn<
  ProjectWithStatistic,
  ProjectWithStatistic
>(
  [
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
      id: "created_at",
      label: "Created",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
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
      id: "total_estimated_cost",
      label: "Total cost",
      type: COLUMN_TYPE.cost,
      cell: CostCell as never,
    },
  ],
  {},
);

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const ObservabilitySection: React.FunctionComponent = () => {
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

  const handleNewProjectClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-12">
      <h2 className="comet-body-accented truncate break-words pb-3">
        Observability
      </h2>
      <DataTable
        columns={COLUMNS}
        data={projects}
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
