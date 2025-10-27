import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { StringParam, NumberParam, useQueryParam } from "use-query-params";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useAppStore from "@/store/AppStore";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { formatDate } from "@/lib/date";
import { ColumnData, COLUMN_TYPE, COLUMN_SELECT_ID, COLUMN_NAME_ID } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { ColumnPinningState } from "@tanstack/react-table";
import { generateActionsColumDef, generateSelectColumDef } from "@/components/shared/DataTable/utils";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Plus } from "lucide-react";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import useDashboardDeleteMutation from "@/api/dashboards/useDashboardDeleteMutation";
import { Dashboard } from "@/types/dashboards";
import { DashboardRowActionsCell } from "./DashboardRowActionsCell";
import AddEditDashboardDialog from "./AddEditDashboardDialog";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";

export const getRowId = (d: Dashboard) => d.id;

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const PAGINATION_SIZE_KEY = "dashboards-pagination-size";

const DashboardsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const projectId = useProjectIdFromURL();

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size = 10, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });

  const [openDialog, setOpenDialog] = useState(false);
  const [selectedDashboardId, setSelectedDashboardId] = useState<string>();

  const { data, isPending } = useDashboardsList(
    {
      workspaceName,
      projectId: projectId ?? undefined,
      page: page!,
      size: size!,
      name: search ?? undefined,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const dashboards = data?.content ?? [];
  const total = data?.total ?? 0;
  const noDataText = search ? "No search results" : "No dashboards yet";

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
      navigate({
        to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId",
        params: {
          workspaceName,
          projectId: projectId!,
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
    }),
    [],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="h-full overflow-auto pb-6 pt-6">
      <div className="container mx-auto max-w-[1440px] px-6">
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
            />
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
              resizeConfig={resizeConfig}
              getRowId={getRowId}
              columnPinning={DEFAULT_COLUMN_PINNING}
            />
            <DataTablePagination
              page={page!}
              pageChange={setPage}
              size={size!}
              sizeChange={setSize}
              total={total}
            />
          </>
        )}

        <AddEditDashboardDialog
          open={openDialog}
          setOpen={setOpenDialog}
          dashboardId={selectedDashboardId}
        />
      </div>
    </div>
  );
};

export default DashboardsPage;

