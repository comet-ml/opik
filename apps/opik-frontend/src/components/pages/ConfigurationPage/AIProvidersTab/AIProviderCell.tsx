import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { PROVIDERS } from "@/constants/providers";
import { ProviderKey, PROVIDER_TYPE } from "@/types/providers";

const AIProviderCell = (context: CellContext<ProviderKey, PROVIDER_TYPE>) => {
  const provider = context.getValue();
  const row = context.row.original;
  const Icon = PROVIDERS[provider]?.icon || null;

  // For custom providers, show the provider name (keyName)
  // For other providers, show the label from PROVIDERS constant
  const providerKeyLabel =
    provider === PROVIDER_TYPE.CUSTOM
      ? row.keyName || "Custom Provider"
      : PROVIDERS[provider]?.label || "";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="flex gap-1"
    >
      {Icon && <Icon className="text-foreground" />}
      <span>{providerKeyLabel}</span>
    </CellWrapper>
  );
};

export default AIProviderCell;
