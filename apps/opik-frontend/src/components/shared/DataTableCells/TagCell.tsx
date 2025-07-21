import React from "react";
import { CellContext } from "@tanstack/react-table";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import { Tag } from "@/components/ui/tag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

type CustomMeta = {
  colored: boolean;
};

const TagCell = (context: CellContext<unknown, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { colored = true } = (custom ?? {}) as CustomMeta;
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {colored ? (
        <ColoredTag label={value}></ColoredTag>
      ) : (
        <Tag size="md">{value}</Tag>
      )}
    </CellWrapper>
  );
};

export default TagCell;
