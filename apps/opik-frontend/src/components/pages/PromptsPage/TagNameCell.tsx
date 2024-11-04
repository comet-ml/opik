import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Tag } from "@/components/ui/tag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import {ArrowUpRight} from "lucide-react";
import {Prompt} from "@/types/prompts";


const TagNameCell = (context: CellContext<Prompt, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag size="lg" variant="gray" className="flex items-center gap-2">
        <p className="truncate">{value}</p>
        <ArrowUpRight className="size-4 shrink-0" />
      </Tag>
    </CellWrapper>
  );
};

export default TagNameCell;
