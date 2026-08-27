import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { Plus } from "lucide-react";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";

import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import AddEditEnvironmentDialog from "@/v2/pages-shared/environments/AddEditEnvironmentDialog/AddEditEnvironmentDialog";
import EnvironmentsRowActionsCell from "@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentsRowActionsCell";
import EnvironmentsActionsPanel from "@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentsActionsPanel";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import IdCell from "@/shared/DataTableCells/IdCell";
import EnvironmentNameCell from "@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentNameCell";
import emptyEnvironmentsLightImage from "/images/empty-environments-light.svg";
import emptyEnvironmentsDarkImage from "/images/empty-environments-dark.svg";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import SearchInput from "@/shared/SearchInput/SearchInput";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { Environment, ENVIRONMENT_WORKSPACE_LIMIT } from "@/types/environments";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
export const getRowId = (e: Environment) => e.id;

const SELECTED_COLUMNS_KEY = "environments-selected-columns";
const COLUMNS_WIDTH_KEY = "environments-columns-width";
const COLUMNS_ORDER_KEY = "environments-columns-order";

export const DEFAULT_COLUMNS: ColumnData<Environment>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: EnvironmentNameCell as never,
    sortable: true,
  },
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "description",
  "created_at",
  "created_by",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  "description",
  "created_at",
  "created_by",
];

const EnvironmentsTab: React.FunctionComponent = () => {
  const newEnvironmentDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const {
    permissions: { canConfigureWorkspaceSettings },
  } = usePermissions();

  const { data, isPending, isPlaceholderData, isFetching } =
    useEnvironmentsList({
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    });

  const allEnvironments = useMemo(() => data?.content ?? [], [data?.content]);

  const environments = useMemo(() => {
    if (!search) return allEnvironments;
    const needle = search.toLowerCase();
    return allEnvironments.filter((env) =>
      env.name.toLowerCase().includes(needle),
    );
  }, [allEnvironments, search]);

  const total = allEnvironments.length;
  const showCreate = !search;
  const atLimit = total >= ENVIRONMENT_WORKSPACE_LIMIT;

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: DEFAULT_COLUMNS_ORDER,
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const selectedRows: Environment[] = useMemo(
    () => environments.filter((row) => rowSelection[row.id]),
    [rowSelection, environments],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Environment>(),
      ...convertColumnDataToColumn<Environment, Environment>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
      }),
      ...(canConfigureWorkspaceSettings
        ? [generateActionsColumDef({ cell: EnvironmentsRowActionsCell })]
        : []),
    ];
  }, [columnsOrder, selectedColumns, canConfigureWorkspaceSettings]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleNewEnvironmentClick = useCallback(() => {
    setOpenDialog(true);
    newEnvironmentDialogKeyRef.current = newEnvironmentDialogKeyRef.current + 1;
  }, []);

  const isTableLoading =
    isPending || (isPlaceholderData && environments.length === 0);

  const createButton = canConfigureWorkspaceSettings ? (
    <Button
      variant="default"
      size="xs"
      onClick={handleNewEnvironmentClick}
      disabled={atLimit}
    >
      <Plus className="mr-1 size-4" />
      Create environment
    </Button>
  ) : null;

  const headerAction =
    createButton && atLimit ? (
      <TooltipWrapper
        content={`A workspace can have up to ${ENVIRONMENT_WORKSPACE_LIMIT} environments. Delete an existing one to create a new environment.`}
      >
        <span>{createButton}</span>
      </TooltipWrapper>
    ) : (
      createButton
    );

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="comet-title-xs">Environments</h2>
        {headerAction}
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
          dimension="sm"
        />

        <div className="flex items-center gap-2">
          {canConfigureWorkspaceSettings && (
            <EnvironmentsActionsPanel environments={selectedRows} />
          )}
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
        </div>
      </div>
      <DataTable
        columns={columns}
        data={environments}
        resizeConfig={resizeConfig}
        selectionConfig={{ rowSelection, setRowSelection }}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          showCreate ? (
            <DataTableEmptyContent
              lightImageUrl={emptyEnvironmentsLightImage}
              darkImageUrl={emptyEnvironmentsDarkImage}
              title="No environments yet"
              description="Create an environment to organize traces by deployment stage (development, staging, production, …)."
            >
              {canConfigureWorkspaceSettings && (
                <button
                  onClick={handleNewEnvironmentClick}
                  className="comet-body-s underline underline-offset-4 hover:text-primary"
                >
                  Add environment
                </button>
              )}
            </DataTableEmptyContent>
          ) : (
            <DataTableNoMatchingData />
          )
        }
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
      />
      <AddEditEnvironmentDialog
        key={newEnvironmentDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default EnvironmentsTab;
