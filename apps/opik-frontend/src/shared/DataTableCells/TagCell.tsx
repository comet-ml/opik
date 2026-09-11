import { CellContext } from "@tanstack/react-table";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import { Tag, TagProps } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";

type CustomMeta = {
  colored?: boolean;
  variantMap?: Record<string, TagProps["variant"]>;
};

const TagCell = (context: CellContext<unknown, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { colored = true, variantMap } = (custom ?? {}) as CustomMeta;
  const value = context.getValue();
  const fixedVariant = variantMap?.[value];
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {colored ? (
        <ColoredTag label={value} variant={fixedVariant} size={tagSize} />
      ) : (
        <Tag size={tagSize}>{value}</Tag>
      )}
    </CellWrapper>
  );
};

export default TagCell;
