import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { PROVIDERS } from "@/constants/providers";
import { ProviderObject, PROVIDER_TYPE } from "@/types/providers";
import { getProviderDisplayName } from "@/lib/provider";

const AIProviderCell = (
  context: CellContext<ProviderObject, PROVIDER_TYPE>,
) => {
  const row = context.row.original;
  const Icon = PROVIDERS[row.provider]?.icon || null;
  const providerKeyLabel = getProviderDisplayName(row);

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
