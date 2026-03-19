import React from "react";
import { CellContext, ColumnMeta, TableMeta } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import { useEffectiveItemExecutionPolicy } from "@/hooks/useEffectiveItemExecutionPolicy";
import { useEffectiveExecutionPolicy } from "@/hooks/useEffectiveExecutionPolicy";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

interface ExecutionPolicyCellInnerProps {
  itemId: string;
  item: DatasetItem;
  metadata: ColumnMeta<DatasetItem, unknown> | undefined;
  tableMetadata: TableMeta<DatasetItem> | undefined;
}

const ExecutionPolicyCellInner: React.FC<ExecutionPolicyCellInnerProps> = ({
  itemId,
  item,
  metadata,
  tableMetadata,
}) => {
  const suiteId = useSuiteIdFromURL();
  const globalPolicy = useEffectiveExecutionPolicy(suiteId);
  const localPolicy = useEffectiveItemExecutionPolicy(
    itemId,
    item.execution_policy,
  );

  if (localPolicy === null) {
    return (
      <CellWrapper metadata={metadata} tableMetadata={tableMetadata}>
        <span className="text-light-slate">
          {globalPolicy.pass_threshold} of {globalPolicy.runs_per_item} must
          pass
        </span>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper metadata={metadata} tableMetadata={tableMetadata}>
      <span>
        {localPolicy.pass_threshold} of {localPolicy.runs_per_item} must pass
      </span>
    </CellWrapper>
  );
};

export const ExecutionPolicyCell: React.FC<
  CellContext<DatasetItem, unknown>
> = (context) => {
  const item = context.row.original;

  return (
    <ExecutionPolicyCellInner
      itemId={item.id}
      item={item}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    />
  );
};
