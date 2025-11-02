import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { PROVIDERS, getProviderKeyLabel } from "@/constants/providers";
import { ProviderKey, PROVIDER_TYPE } from "@/types/providers";

const AIProviderCell = (context: CellContext<ProviderKey, PROVIDER_TYPE>) => {
  const provider = context.getValue();
  const row = context.row.original;
  const Icon = PROVIDERS[provider]?.icon || null;
  const providerKeyLabel = getProviderKeyLabel(provider, row.keyName);

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
