import { CellContext } from "@tanstack/react-table";

import { Tag, TagProps } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { DATASET_TYPE } from "@/types/datasets";
import { TYPE_LABELS } from "@/v2/pages/EvaluationSuitesPage/columns";

const VARIANT_MAP: Record<string, TagProps["variant"]> = {
  [DATASET_TYPE.EVALUATION_SUITE]: "purple",
  [DATASET_TYPE.DATASET]: "yellow",
};

const DatasetTypeCell = (context: CellContext<unknown, unknown>) => {
  const { column, table } = context;
  const value = (context.getValue() as string) ?? DATASET_TYPE.DATASET;

  const variant = VARIANT_MAP[value] ?? "yellow";
  const label = TYPE_LABELS[value] ?? "Dataset";

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
      className="gap-1"
    >
      <Tag variant={variant} size="md">
        {label}
      </Tag>
    </CellWrapper>
  );
};

export default DatasetTypeCell;
