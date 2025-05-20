import React, { useMemo, useRef, useState } from "react";
import { ColumnPinningState } from "@tanstack/react-table";

import { convertColumnDataToColumn } from "@/lib/table";
import { ProviderKey } from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useAppStore from "@/store/AppStore";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import { formatDate } from "@/lib/date";
import { PROVIDERS } from "@/constants/providers";
import AIProviderCell from "@/components/pages/ConfigurationPage/AIProvidersTab/AIProviderCell";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import AIProvidersRowActionsCell from "@/components/pages/ConfigurationPage/AIProvidersTab/AIProvidersRowActionsCell";
import { areAllProvidersConfigured } from "@/lib/provider";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Button } from "@/components/ui/button";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";

export const DEFAULT_COLUMNS: ColumnData<ProviderKey>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => PROVIDERS[row.provider]?.apiKeyName,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "provider",
    label: "Provider",
    type: COLUMN_TYPE.string,
    cell: AIProviderCell as never,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_NAME_ID],
  right: [],
};

const AIProvidersTab = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [search, setSearch] = useState("");
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const { data, isPending } = useProviderKeys(
    {
      workspaceName,
    },
    {
      refetchInterval: 30000,
    },
  );

  const providerKeys = useMemo(() => data?.content ?? [], [data?.content]);

  const filteredProviderKeys = useMemo(() => {
    if (providerKeys?.length === 0 || search === "") {
      return providerKeys;
    }

    const searchLowerCase = search.toLowerCase();

    return providerKeys.filter((p) => {
      const providerDetails = PROVIDERS[p.provider];

      return (
        providerDetails.apiKeyName.toLowerCase().includes(searchLowerCase) ||
        providerDetails.value.toLowerCase().includes(searchLowerCase)
      );
    });
  }, [providerKeys, search]);

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<ProviderKey, ProviderKey>(
        DEFAULT_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: AIProvidersRowActionsCell,
      }),
    ];
  }, []);

  const handleAddConfigurationClick = () => {
    resetDialogKeyRef.current += 1;
    setOpenDialog(true);
  };

  const noDataLabel =
    search === ""
      ? "Configure AI providers to use the playground and online scoring."
      : "No search results";

  if (isPending) {
    return <Loader />;
  }

  return (
    <>
      <div>
        <div className="mb-4 flex w-full items-center justify-between">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            className="w-[320px]"
            placeholder="Search by name"
            dimension="sm"
          />
          <Button
            onClick={handleAddConfigurationClick}
            size="sm"
            disabled={areAllProvidersConfigured(providerKeys)}
          >
            Add configuration
          </Button>
        </div>

        <DataTable
          columns={columns}
          data={filteredProviderKeys}
          columnPinning={DEFAULT_COLUMN_PINNING}
          noData={
            <DataTableNoData title={noDataLabel}>
              {search === "" && (
                <Button variant="link" onClick={handleAddConfigurationClick}>
                  Add configuration
                </Button>
              )}
            </DataTableNoData>
          }
        />
      </div>
      <ManageAIProviderDialog
        configuredProvidersList={providerKeys}
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </>
  );
};

export default AIProvidersTab;
