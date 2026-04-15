import { CellContext } from "@tanstack/react-table";

import { Tag, TagProps } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { DATASET_TYPE } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import { TAG_SIZE_MAP } from "@/constants/shared";
import { TYPE_LABELS } from "@/v1/pages/TestSuitesPage/columns";

const VARIANT_MAP: Record<string, TagProps["variant"]> = {
  [DATASET_TYPE.TEST_SUITE]: "purple",
  [DATASET_TYPE.DATASET]: "yellow",
};

const DatasetTypeCell = (context: CellContext<unknown, unknown>) => {
  const { column, table } = context;
  const value = (context.getValue() as string) ?? DATASET_TYPE.DATASET;
  const rowHeight = table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const variant = VARIANT_MAP[value] ?? "yellow";
  const label = TYPE_LABELS[value] ?? "Dataset";
  const tagSize = TAG_SIZE_MAP[rowHeight];

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
      className="gap-1"
    >
      <Tag variant={variant} size={tagSize}>
        {label}
      </Tag>
    </CellWrapper>
  );
};

export default DatasetTypeCell;
