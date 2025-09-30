import React, { useMemo, useRef, useState } from "react";
import { Plus } from "lucide-react";

import { ProviderKey, PROVIDER_TYPE } from "@/types/providers";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import { Button } from "@/components/ui/button";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import { formatDate } from "@/lib/date";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import CustomProviderRowActionsCell from "./CustomProviderRowActionsCell";

export const CUSTOM_PROVIDER_COLUMNS: ColumnData<ProviderKey>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Provider Name",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.keyName || "default",
  },
  {
    id: "base_url",
    label: "Base URL",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.base_url || "-",
  },
  {
    id: "models",
    label: "Models",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => {
      const models = row.configuration?.models;
      if (!models) return "0 models";
      const modelCount = models.split(",").filter((m) => m.trim()).length;
      return `${modelCount} model${modelCount !== 1 ? "s" : ""}`;
    },
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
];

type CustomProvidersSectionProps = {
  providerKeys: ProviderKey[];
};

const CustomProvidersSection: React.FC<CustomProvidersSectionProps> = ({
  providerKeys,
}) => {
  const customProviders = useMemo(() => {
    return providerKeys.filter((pk) => pk.provider === PROVIDER_TYPE.CUSTOM);
  }, [providerKeys]);

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<ProviderKey, ProviderKey>(
        CUSTOM_PROVIDER_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: CustomProviderRowActionsCell,
      }),
    ];
  }, []);

  return (
    <div className="mt-8">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h3 className="comet-title-m">Custom Providers</h3>
          <p className="comet-body-s text-muted-foreground">
            Configure multiple OpenAI API-compatible providers (Ollama, vLLM,
            LM Studio, etc.)
          </p>
        </div>
      </div>

      <DataTable
        columns={columns}
        data={customProviders}
        noData={
          <DataTableNoData title="No custom providers configured">
            <p className="comet-body-s text-muted-foreground">
              Click "Add configuration" above to add a custom provider
            </p>
          </DataTableNoData>
        }
      />
    </div>
  );
};

export default CustomProvidersSection;
