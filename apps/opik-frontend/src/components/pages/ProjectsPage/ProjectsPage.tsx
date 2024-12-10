import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import useProjectsList from "@/api/projects/useProjectsList";
import { Project } from "@/types/projects";
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
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";

export const getRowId = (p: Project) => p.id;

const SELECTED_COLUMNS_KEY = "projects-selected-columns";
const COLUMNS_WIDTH_KEY = "projects-columns-width";
const COLUMNS_ORDER_KEY = "projects-columns-order";
const COLUMNS_SORT_KEY = "projects-columns-sort";

export const DEFAULT_COLUMNS: ColumnData<Project>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
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

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "last_updated_at",
  "created_at",
  "description",
];

export const DEFAULT_SORTING_COLUMNS: ColumnSort[] = [
  {
    id: "last_updated_at",
    desc: true,
  },
];

const ProjectsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING_COLUMNS,
    },
  );

  const { data, isPending } = useProjectsList(
    {
      workspaceName,
      search,
      sorting: sortedColumns.map((column) => {
        if (column.id === "last_updated_at") {
          return {
            ...column,
            id: "last_updated_trace_at",
          };
        }
        return column;
      }),
      page,
      size,
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
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
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

  const selectedRows: Project[] = useMemo(() => {
    return projects.filter((row) => rowSelection[row.id]);
  }, [rowSelection, projects]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Project>(),
      mapColumnDataFields<Project, Project>({
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        sortable: true,
        customMeta: {
          nameKey: "name",
          idKey: "id",
          resource: RESOURCE_TYPE.project,
        },
      }),
      ...convertColumnDataToColumn<Project, Project>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
      }),
      generateActionsColumDef({
        cell: ProjectRowActionsCell,
      }),
    ];
  }, [selectedColumns, columnsOrder]);

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
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Projects</h1>
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
        ></SearchInput>
        <div className="flex items-center gap-2">
          <ProjectsActionsPanel projects={selectedRows} />
          <Separator orientation="vertical" className="ml-2 mr-2.5 h-6" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" onClick={handleNewProjectClick}>
            Create new project
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={projects}
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
      />
      <div className="py-4">
        <DataTablePagination
          page={page}
          pageChange={setPage}
          size={size}
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
