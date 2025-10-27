import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate, useParams } from "@tanstack/react-router";
import { StringParam, NumberParam, useQueryParam } from "use-query-params";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import useAppStore from "@/store/AppStore";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { formatDate } from "@/lib/date";
import { ColumnData, COLUMN_TYPE, COLUMN_SELECT_ID, COLUMN_NAME_ID } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { ColumnPinningState } from "@tanstack/react-table";
import { generateActionsColumDef, generateSelectColumDef } from "@/components/shared/DataTable/utils";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Plus, X } from "lucide-react";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import useDashboardDeleteMutation from "@/api/dashboards/useDashboardDeleteMutation";
import { Dashboard } from "@/types/dashboards";
import { DashboardRowActionsCell } from "./DashboardRowActionsCell";
import AddEditDashboardDialog from "./AddEditDashboardDialog";
import useLocalStorageState from "use-local-storage-state";
import { ColumnSort } from "@tanstack/react-table";

export const getRowId = (d: Dashboard) => d.id;

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const DEFAULT_SORTING_COLUMNS: ColumnSort[] = [
  {
    id: "last_updated_at",
    desc: true,
  },
];

const COLUMNS_SORT_KEY = "dashboards-columns-sort";
const COLUMNS_WIDTH_KEY = "dashboards-columns-width";
const PAGINATION_SIZE_KEY = "dashboards-pagination-size";

const DashboardsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const params = useParams({ strict: false });
  const projectId = params.projectId as string | undefined;

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size = 10, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });

  const [type, setType] = useQueryParam("type", StringParam, {
    updateType: "replaceIn",
  });

  const [openDialog, setOpenDialog] = useState(false);
  const [selectedDashboardId, setSelectedDashboardId] = useState<string>();

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING_COLUMNS,
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const { data, isPending } = useDashboardsList(
    {
      workspaceName,
      projectId: projectId ?? undefined,
      page: page!,
      size: size!,
      name: search ?? undefined,
      type: type ?? undefined,
      sorting: sortedColumns,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const dashboards = data?.content ?? [];
  const total = data?.total ?? 0;
  const noDataText = search || type ? "No search results" : "No dashboards yet";

  const deleteMutation = useDashboardDeleteMutation();

  const handleDeleteDashboard = useCallback(
    (dashboardId: string) => {
      deleteMutation.mutate({
        dashboardId,
        workspaceName,
      });
    },
    [deleteMutation, workspaceName],
  );

  const handleRowClick = useCallback(
    (dashboard: Dashboard) => {
      // Use projectId from URL if available, otherwise use first project from dashboard
      const targetProjectId = projectId || dashboard.project_ids?.[0];
      
      if (!targetProjectId) {
        console.error("No project ID available for dashboard navigation");
        return;
      }
      
      navigate({
        to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId",
        params: {
          workspaceName,
          projectId: targetProjectId,
          dashboardId: dashboard.id,
        },
      });
    },
    [navigate, workspaceName, projectId],
  );

  const columnsDef: ColumnData<Dashboard>[] = useMemo(() => {
    return [
      {
        id: "name",
        label: "Dashboard name",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        sortable: true,
        customMeta: {
          nameKey: "name",
          idKey: "id",
          resource: RESOURCE_TYPE.dashboard,
        },
      },
      {
        id: "description",
        label: "Description",
        type: COLUMN_TYPE.string,
        sortable: false,
      },
      {
        id: "type",
        label: "Type",
        type: COLUMN_TYPE.string,
        sortable: true,
        accessorFn: (row: Dashboard) => (row.type === "prebuilt" ? "Prebuilt" : "Custom"),
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        accessorFn: (row: Dashboard) => row.created_at ? formatDate(row.created_at) : "-",
        sortable: true,
      },
      {
        id: "last_updated_at",
        label: "Last updated",
        type: COLUMN_TYPE.time,
        accessorFn: (row: Dashboard) => row.last_updated_at ? formatDate(row.last_updated_at) : "-",
        sortable: true,
      },
    ];
  }, []);

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<Dashboard, Dashboard>(columnsDef, {});

    return [
      generateSelectColumDef<Dashboard>(),
      ...retVal,
      generateActionsColumDef({
        cell: DashboardRowActionsCell,
      }),
    ];
  }, [columnsDef]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="comet-title-l">Dashboards</h1>
          <p className="comet-body-s text-light-slate">
            Create and manage custom dashboards to visualize your project metrics
          </p>
        </div>
        <Button
          onClick={() => {
            setSelectedDashboardId(undefined);
            setOpenDialog(true);
          }}
        >
          <Plus className="mr-2 size-4" />
          Create dashboard
        </Button>
      </div>

      <Separator className="mb-4" />

      <div className="mb-4 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search ?? ""}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          />
          <Select
            value={type ?? "all"}
            onValueChange={(value) => setType(value === "all" ? undefined : value)}
          >
            <SelectTrigger className="w-[180px]">
              <SelectValue placeholder="All types" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All types</SelectItem>
              <SelectItem value="custom">Custom</SelectItem>
              <SelectItem value="prebuilt">Prebuilt</SelectItem>
            </SelectContent>
          </Select>
          {(search || type) && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setSearch(undefined);
                setType(undefined);
              }}
              className="h-8 px-2"
            >
              <X className="size-4 mr-1" />
              Clear filters
            </Button>
          )}
        </div>
      </div>

      {!isPending && total === 0 && (
        <DataTableNoData title={noDataText} />
      )}

      {total > 0 && (
        <>
          <DataTable
            columns={columns}
            data={dashboards}
            onRowClick={handleRowClick}
            sortConfig={{
              enabled: true,
              sorting: sortedColumns,
              setSorting: setSortedColumns,
            }}
            resizeConfig={resizeConfig}
            getRowId={getRowId}
            columnPinning={DEFAULT_COLUMN_PINNING}
          />
          <div className="py-4">
            <DataTablePagination
              page={page!}
              pageChange={setPage}
              size={size!}
              sizeChange={setSize}
              total={total}
            />
          </div>
        </>
      )}

      <AddEditDashboardDialog
        open={openDialog}
        setOpen={setOpenDialog}
        dashboardId={selectedDashboardId}
      />
    </div>
  );
};

export default DashboardsPage;

