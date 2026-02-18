import React from "react";
import { CellContext } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import { useItemAddedBehaviors, useItemDeletedBehaviorIds } from "@/store/EvaluationSuiteDraftStore";

const BehaviorsCountCellInner: React.FC<{ itemId: string }> = ({
  itemId,
}) => {
  const addedBehaviors = useItemAddedBehaviors(itemId);
  const deletedBehaviorIds = useItemDeletedBehaviorIds(itemId);

  // TODO: Once OPIK-4225 lands, read server evaluator count from item data
  const serverCount = 0;
  const count = serverCount + addedBehaviors.size - deletedBehaviorIds.size;

  if (count <= 0) {
    return <span className="text-muted-slate">&mdash;</span>;
  }

  return (
    <span>
      {count} behavior{count !== 1 ? "s" : ""}
    </span>
  );
};

export function createBehaviorsCountCell(_datasetId: string) {
  const BehaviorsCountCell = (context: CellContext<DatasetItem, unknown>) => {
    const itemId = context.row.original.id;
    return <BehaviorsCountCellInner itemId={itemId} />;
  };
  return BehaviorsCountCell;
}
