import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import { useNavigate } from "@tanstack/react-router";
import capitalize from "lodash/capitalize";

import useAlertsList from "@/api/alerts/useAlertsList";
import AlertsRowActionsCell from "@/components/pages/ConfigurationPage/AlertsTab/AlertsRowActionsCell";
import AlertsEventsCell from "@/components/pages/ConfigurationPage/AlertsTab/AlertsEventsCell";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import StatusCell from "@/components/shared/DataTableCells/StatusCell";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { Alert, ALERT_TYPE } from "@/types/alerts";
import {
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import {
  convertColumnDataToColumn,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import { formatDate } from "@/lib/date";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { Separator } from "@/components/ui/separator";
import AlertsActionsPanel from "@/components/pages/ConfigurationPage/AlertsTab/AlertsActionsPanel";

export const getRowId = (a: Alert) => a.id!;

const SELECTED_COLUMNS_KEY = "alerts-selected-columns";
const COLUMNS_WIDTH_KEY = "alerts-columns-width";
const COLUMNS_ORDER_KEY = "alerts-columns-order";
const COLUMNS_SORT_KEY = "alerts-columns-sort";
const PAGINATION_SIZE_KEY = "alerts-pagination-size";

export const DEFAULT_COLUMNS: ColumnData<Alert>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "alert_type",
    label: "Type",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => {
      if (!row.alert_type) return capitalize(ALERT_TYPE.general);
      return capitalize(row.alert_type);
    },
  },
  {
    id: "webhook_url",
    label: "Endpoint",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.webhook?.url || "-",
  },
  {
    id: "triggers",
    label: "Events",
    type: COLUMN_TYPE.string,
    cell: AlertsEventsCell as never,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.created_by || "-",
  },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.string,
    cell: StatusCell as never,
    accessorFn: (row) => row.enabled,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => (row.created_at ? formatDate(row.created_at) : "-"),
  },
  {
    id: "last_updated_at",
    label: "Updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) =>
      row.last_updated_at ? formatDate(row.last_updated_at) : "-",
  },
];

export const FILTERS_COLUMNS: ColumnData<Alert>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "alert_type",
    label: "Type",
    type: COLUMN_TYPE.category,
  },
  {
    id: "webhook_url",
    label: "Endpoint",
    type: COLUMN_TYPE.string,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: "Updated",
    type: COLUMN_TYPE.time,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "alert_type",
  "webhook_url",
  "triggers",
  "created_by",
  "status",
];

const AlertsTab: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const [search = "", setSearch] = useQueryParam("alerts_search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam(
    "alerts_filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [page, setPage] = useState(1);
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [],
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        alert_type: {
          keyComponentProps: {
            options: Object.values(ALERT_TYPE).map((type) => ({
              value: type,
              label: capitalize(type),
            })),
            placeholder: "Select type",
          },
        },
      } as Record<string, { keyComponentProps: Record<string, unknown> }>,
    }),
    [],
  );

  const { data, isPending } = useAlertsList(
    {
      workspaceName,
      search: search!,
      filters,
      sorting: sortedColumns,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    },
  );

  const alerts = useMemo(() => data?.content ?? [], [data?.content]);
  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );
  const total = data?.total ?? 0;
  const noData = !search && filters.length === 0;
  const noDataText = noData ? "There are no alerts yet" : "No search results";

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

  const selectedRows: Alert[] = useMemo(() => {
    return alerts.filter((row) => rowSelection[row.id!]);
  }, [rowSelection, alerts]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Alert>(),
      mapColumnDataFields<Alert, Alert>({
        id: "name",
        label: "Name",
        type: COLUMN_TYPE.string,
        sortable: isColumnSortable("name", sortableBy),
      }),
      ...convertColumnDataToColumn<Alert, Alert>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      generateActionsColumDef({
        cell: AlertsRowActionsCell,
      }),
    ];
  }, [columnsOrder, selectedColumns, sortableBy]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [sortedColumns, setSortedColumns],
  );

  const handleNewAlertClick = useCallback(() => {
    navigate({
      to: "/$workspaceName/configuration/alerts/new",
      params: { workspaceName },
      search: (prev) => prev, // Preserve existing search params
    });
  }, [navigate, workspaceName]);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
          <FiltersButton
            columns={FILTERS_COLUMNS}
            filters={filters}
            onChange={setFilters}
            config={filtersConfig}
          />
        </div>

        <div className="flex items-center gap-2">
          <AlertsActionsPanel alerts={selectedRows} />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" size="sm" onClick={handleNewAlertClick}>
            Create new alert
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={alerts}
        resizeConfig={resizeConfig}
        sortConfig={sortConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewAlertClick}>
                Create new alert
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
    </div>
  );
};

export default AlertsTab;
