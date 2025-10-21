import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";

const AIProviderCell = (context: CellContext<unknown, PROVIDER_TYPE>) => {
  const provider = context.getValue();
  const Icon = PROVIDERS[provider]?.icon || null;

  const providerKeyLabel = PROVIDERS[provider]?.label || "";

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
