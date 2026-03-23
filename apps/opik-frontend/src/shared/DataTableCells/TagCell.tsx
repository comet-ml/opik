import React from "react";
import { CellContext } from "@tanstack/react-table";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import { Tag, TagProps } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

type CustomMeta = {
  colored?: boolean;
  variantMap?: Record<string, TagProps["variant"]>;
};

const TagCell = (context: CellContext<unknown, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { colored = true, variantMap } = (custom ?? {}) as CustomMeta;
  const value = context.getValue();
  const fixedVariant = variantMap?.[value];

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {colored ? (
        <ColoredTag label={value} variant={fixedVariant} />
      ) : (
        <Tag size="md">{value}</Tag>
      )}
    </CellWrapper>
  );
};

export default TagCell;
