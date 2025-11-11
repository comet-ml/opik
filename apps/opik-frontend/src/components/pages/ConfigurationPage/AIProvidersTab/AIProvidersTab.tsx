import React, { useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { ColumnPinningState } from "@tanstack/react-table";

import { convertColumnDataToColumn } from "@/lib/table";
import { ProviderObject, PROVIDER_TYPE } from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useAppStore from "@/store/AppStore";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import { formatDate } from "@/lib/date";
import { PROVIDERS, LEGACY_CUSTOM_PROVIDER_NAME } from "@/constants/providers";
import AIProviderCell from "@/components/pages/ConfigurationPage/AIProvidersTab/AIProviderCell";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import AIProvidersRowActionsCell from "@/components/pages/ConfigurationPage/AIProvidersTab/AIProvidersRowActionsCell";
import Loader from "@/components/shared/Loader/Loader";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Button } from "@/components/ui/button";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

// Hook to get translated columns
const useAIProvidersColumns = (): ColumnData<ProviderObject>[] => {
  const { t, i18n } = useTranslation();
  
  return useMemo(() => [
    {
      id: COLUMN_NAME_ID,
      label: t("configuration.aiProviders.columns.name"),
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
      label: t("configuration.aiProviders.columns.created"),
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
    },
    {
      id: "provider",
      label: t("configuration.aiProviders.columns.provider"),
      type: COLUMN_TYPE.string,
      cell: AIProviderCell as never,
    },
  ], [t, i18n.language]);
};

// Legacy export for backward compatibility
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
  const { t } = useTranslation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  
  // Use translated columns
  const DEFAULT_COLUMNS = useAIProvidersColumns();

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
    return [
      ...convertColumnDataToColumn<ProviderObject, ProviderObject>(
        DEFAULT_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: AIProvidersRowActionsCell,
      }),
    ];
  }, [DEFAULT_COLUMNS]);

  const handleAddConfigurationClick = () => {
    resetDialogKeyRef.current += 1;
    setOpenDialog(true);
  };

  const noDataLabel =
    search === ""
      ? t("configuration.aiProviders.noProviders")
      : t("common.noSearchResults");

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
          placeholder={t("configuration.aiProviders.searchPlaceholder")}
          dimension="sm"
        />
        <Button onClick={handleAddConfigurationClick} size="sm">
          {t("configuration.aiProviders.addProvider")}
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
                {t("configuration.aiProviders.addProvider")}
              </Button>
            )}
          </DataTableNoData>
        }
      />

      <ManageAIProviderDialog
        configuredProvidersList={providerKeys}
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default AIProvidersTab;
