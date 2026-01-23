import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { PROVIDERS } from "@/constants/providers";
import { ProviderObject, PROVIDER_TYPE } from "@/types/providers";
import { getProviderDisplayName } from "@/lib/provider";
import { Tag } from "@/components/ui/tag";

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
      className="flex items-center gap-2"
    >
      <div className="flex items-center gap-1">
        {Icon && <Icon className="text-foreground" />}
        <span>{providerKeyLabel}</span>
      </div>
      {row.read_only && (
        <Tag size="sm" variant="gray">
          Read-only
        </Tag>
      )}
    </CellWrapper>
  );
};

export default AIProviderCell;
