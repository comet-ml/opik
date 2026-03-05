import { CellContext } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import { useEditedDatasetItemById } from "@/store/EvaluationSuiteDraftStore";

interface ExecutionPolicyCellInnerProps {
  itemId: string;
  item: DatasetItem;
}

function ExecutionPolicyCellInner({
  itemId,
  item,
}: ExecutionPolicyCellInnerProps) {
  const editedItem = useEditedDatasetItemById(itemId);
  // Use `in` to detect explicit undefined (user cleared the override).
  // See ItemExecutionPolicySection.tsx for a detailed explanation.
  const hasEditedPolicy =
    editedItem != null && "execution_policy" in editedItem;
  const policy = hasEditedPolicy
    ? editedItem.execution_policy ?? null
    : item.execution_policy ?? null;

  if (policy === null) {
    return <span className="text-muted-slate">&mdash;</span>;
  }

  return (
    <span>
      {policy.runs_per_item} run{policy.runs_per_item !== 1 ? "s" : ""},{" "}
      {policy.pass_threshold} to pass
    </span>
  );
}

export function ExecutionPolicyCell(
  context: CellContext<DatasetItem, unknown>,
) {
  const item = context.row.original;
  return <ExecutionPolicyCellInner itemId={item.id} item={item} />;
}
