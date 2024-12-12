import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";
import { Book } from "lucide-react";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import useProjectsList from "@/api/projects/useProjectsList";
import Loader from "@/components/shared/Loader/Loader";
import AddEditProjectDialog from "@/components/pages/ProjectsPage/AddEditProjectDialog";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { COLUMN_NAME_ID, COLUMN_SELECT_ID, COLUMN_TYPE } from "@/types/shared";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Project } from "@/types/projects";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn } from "@/lib/table";
import { buildDocsUrl } from "@/lib/utils";

const COLUMNS_WIDTH_KEY = "home-projects-columns-width";

export const COLUMNS = convertColumnDataToColumn<Project, Project>(
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

  const { data, isPending } = useProjectsList(
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
    <div className="pb-4">
      <div className="flex items-center justify-between gap-8 pb-4 pt-2">
        <div className="flex items-center gap-2">
          <h2 className="comet-body-accented truncate break-words">
            Observability
          </h2>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" asChild>
            <a
              href={buildDocsUrl("/tracing/log_traces")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4 shrink-0" />
              Learn more
            </a>
          </Button>
          <Link to="/$workspaceName/projects" params={{ workspaceName }}>
            <Button variant="outline">View all projects</Button>
          </Link>
        </div>
      </div>
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
      <AddEditProjectDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default ObservabilitySection;
