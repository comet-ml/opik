import React, { useMemo, useRef, useState } from "react";
import { ColumnPinningState } from "@tanstack/react-table";

import { convertColumnDataToColumn } from "@/lib/table";
import { ProviderObject, PROVIDER_TYPE } from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useAppStore from "@/store/AppStore";
import ManageAIProviderDialog from "@/v1/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import { PROVIDERS, LEGACY_CUSTOM_PROVIDER_NAME } from "@/constants/providers";
import AIProviderCell from "@/v1/pages/ConfigurationPage/AIProvidersTab/AIProviderCell";
import { generateActionsColumDef } from "@/shared/DataTable/utils";
import AIProvidersRowActionsCell from "@/v1/pages/ConfigurationPage/AIProvidersTab/AIProvidersRowActionsCell";
import Loader from "@/shared/Loader/Loader";
import ExplainerCallout from "@/shared/ExplainerCallout/ExplainerCallout";
import SearchInput from "@/shared/SearchInput/SearchInput";
import { Button } from "@/ui/button";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { usePermissions } from "@/contexts/PermissionsContext";

export const DEFAULT_COLUMNS: ColumnData<ProviderObject>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    accessorFn: (row) =>
      row.provider === PROVIDER_TYPE.CUSTOM
        ? row.provider_name || LEGACY_CUSTOM_PROVIDER_NAME
        : PROVIDERS[row.provider]?.apiKeyName,
  },
  {
    id: "base_url",
    label: "URL",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.base_url || "-",
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
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
  const {
    permissions: { canUpdateAIProviders },
  } = usePermissions();

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
        providerDetails?.apiKeyName?.toLowerCase().includes(searchLowerCase) ||
        providerDetails?.value?.toLowerCase().includes(searchLowerCase) ||
        (p.provider === PROVIDER_TYPE.CUSTOM &&
          (p.provider_name?.toLowerCase().includes(searchLowerCase) ||
            p.base_url?.toLowerCase().includes(searchLowerCase)))
      );
    });
  }, [providerKeys, search]);

  const columns = useMemo(() => {
    const basicColumns = convertColumnDataToColumn<
      ProviderObject,
      ProviderObject
    >(DEFAULT_COLUMNS, {});

    if (canUpdateAIProviders)
      return [
        ...basicColumns,
        generateActionsColumDef({
          cell: AIProvidersRowActionsCell,
        }),
      ];

    return basicColumns;
  }, [canUpdateAIProviders]);

  const handleAddConfigurationClick = () => {
    resetDialogKeyRef.current += 1;
    setOpenDialog(true);
  };

  const getNoDataLabel = () => {
    if (search !== "") return "No search results";
    if (!canUpdateAIProviders) return "No AI providers configured yet.";
    return "Configure AI providers to use the playground and online scoring.";
  };

  if (isPending) {
    return <Loader />;
  }

  return (
    <div>
      <ExplainerCallout
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_an_ai_provider]}
      />
      <div className="mb-4 flex w-full items-center justify-between">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          className="w-[320px]"
          placeholder="Search by name"
          dimension="sm"
        />
        {canUpdateAIProviders && (
          <Button onClick={handleAddConfigurationClick} size="sm">
            Add configuration
          </Button>
        )}
      </div>

      <DataTable
        columns={columns}
        data={filteredProviderKeys}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={getNoDataLabel()}>
            {search === "" && canUpdateAIProviders && (
              <Button variant="link" onClick={handleAddConfigurationClick}>
                Add configuration
              </Button>
            )}
          </DataTableNoData>
        }
      />
      {canUpdateAIProviders && (
        <ManageAIProviderDialog
          configuredProvidersList={providerKeys}
          key={resetDialogKeyRef.current}
          open={openDialog}
          setOpen={setOpenDialog}
        />
      )}
    </div>
  );
};

export default AIProvidersTab;
