import React from "react";
import { CellContext } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import { useItemExecutionPolicy } from "@/store/EvaluationSuiteDraftStore";

const ExecutionPolicyCellInner: React.FC<{ itemId: string }> = ({
  itemId,
}) => {
  const itemPolicy = useItemExecutionPolicy(itemId);

  if (itemPolicy === null) {
    return <span className="text-muted-slate">&mdash;</span>;
  }

  return (
    <span>
      {itemPolicy.runs_per_item} run{itemPolicy.runs_per_item !== 1 ? "s" : ""}
      , {itemPolicy.pass_threshold} to pass
    </span>
  );
};

export function ExecutionPolicyCell(
  context: CellContext<DatasetItem, unknown>,
) {
  const itemId = context.row.original.id;
  return <ExecutionPolicyCellInner itemId={itemId} />;
}
