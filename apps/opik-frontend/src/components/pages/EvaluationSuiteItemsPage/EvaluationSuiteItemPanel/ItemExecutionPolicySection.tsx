import { useCallback, useRef } from "react";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { ExecutionPolicy, MAX_RUNS_PER_ITEM } from "@/types/evaluation-suites";
import {
  useEditItem,
  useEditedDatasetItemById,
} from "@/store/EvaluationSuiteDraftStore";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import { cn } from "@/lib/utils";

interface ItemExecutionPolicySectionProps {
  itemId: string;
  suitePolicy: ExecutionPolicy;
  savedItemPolicy?: ExecutionPolicy;
}

function ItemExecutionPolicySection({
  itemId,
  suitePolicy,
  savedItemPolicy,
}: ItemExecutionPolicySectionProps) {
  const editedItem = useEditedDatasetItemById(itemId);
  const editItem = useEditItem();
  const lastOverrideRef = useRef<ExecutionPolicy | undefined>(undefined);

  // The `in` operator detects when execution_policy was explicitly set to
  // undefined in the draft (i.e. the user toggled OFF "Override global settings").
  // A simple nullish check would miss this because `undefined` values are
  // indistinguishable from absent keys without `in`.
  const hasEditedPolicy =
    editedItem != null && "execution_policy" in editedItem;
  const itemPolicy = hasEditedPolicy
    ? editedItem.execution_policy
    : savedItemPolicy;
  const isOverridden = itemPolicy != null;
  const currentPolicy = itemPolicy ?? suitePolicy;

  const handleToggle = useCallback(
    (checked: boolean) => {
      if (!checked) {
        lastOverrideRef.current = currentPolicy;
      }
      editItem(itemId, {
        execution_policy: checked
          ? lastOverrideRef.current ?? savedItemPolicy ?? { ...suitePolicy }
          : undefined,
      });
    },
    [itemId, suitePolicy, savedItemPolicy, currentPolicy, editItem],
  );

  const onRunsCommit = useCallback(
    (runs: number) => {
      editItem(itemId, {
        execution_policy: {
          runs_per_item: runs,
          pass_threshold: Math.min(currentPolicy.pass_threshold, runs),
        },
      });
    },
    [itemId, currentPolicy, editItem],
  );

  const onThresholdCommit = useCallback(
    (threshold: number) => {
      editItem(itemId, {
        execution_policy: {
          ...currentPolicy,
          pass_threshold: threshold,
        },
      });
    },
    [itemId, currentPolicy, editItem],
  );

  const runsInput = useClampedIntegerInput({
    value: currentPolicy.runs_per_item,
    min: 1,
    max: MAX_RUNS_PER_ITEM,
    onCommit: onRunsCommit,
  });

  const thresholdInput = useClampedIntegerInput({
    value: currentPolicy.pass_threshold,
    min: 1,
    max: currentPolicy.runs_per_item,
    onCommit: onThresholdCommit,
  });

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Execution policy</h3>
      <div className="flex h-8 items-center gap-3">
        <Switch
          size="xs"
          checked={isOverridden}
          onCheckedChange={handleToggle}
        />
        <span className="comet-body-xs text-muted-slate">
          Override global settings
        </span>

        {isOverridden ? (
          <div className="ml-auto flex items-center gap-3">
            <div className="flex items-center gap-2">
              <Input
                dimension="sm"
                className={cn(
                  "w-14 [&::-webkit-inner-spin-button]:appearance-none",
                  {
                    "border-destructive": runsInput.isInvalid,
                  },
                )}
                type="number"
                min={1}
                max={MAX_RUNS_PER_ITEM}
                value={runsInput.displayValue}
                onChange={runsInput.onChange}
                onFocus={runsInput.onFocus}
                onBlur={runsInput.onBlur}
              />
              <span className="comet-body-s text-muted-slate">
                runs per item
              </span>
            </div>
            <div className="flex items-center gap-2">
              <Input
                dimension="sm"
                className={cn(
                  "w-14 [&::-webkit-inner-spin-button]:appearance-none",
                  {
                    "border-destructive": thresholdInput.isInvalid,
                  },
                )}
                type="number"
                min={1}
                max={currentPolicy.runs_per_item}
                value={thresholdInput.displayValue}
                onChange={thresholdInput.onChange}
                onFocus={thresholdInput.onFocus}
                onBlur={thresholdInput.onBlur}
              />
              <span className="comet-body-s text-muted-slate">
                required to pass
              </span>
            </div>
          </div>
        ) : (
          <div className="ml-auto flex items-center gap-2">
            <span className="comet-body-s text-muted-slate">
              {suitePolicy.runs_per_item} run
              {suitePolicy.runs_per_item !== 1 ? "s" : ""} per item,{" "}
              {suitePolicy.pass_threshold} required to pass
            </span>
            <span className="comet-body-xs text-light-slate">
              (suite default)
            </span>
          </div>
        )}
      </div>
    </div>
  );
}

export default ItemExecutionPolicySection;
