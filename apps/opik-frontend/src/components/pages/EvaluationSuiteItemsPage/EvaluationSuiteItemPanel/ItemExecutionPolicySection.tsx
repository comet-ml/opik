import React, { useCallback } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { ExecutionPolicy } from "@/types/evaluation-suites";
import {
  useItemExecutionPolicy,
  useSetItemExecutionPolicy,
} from "@/store/EvaluationSuiteDraftStore";

interface ItemExecutionPolicySectionProps {
  itemId: string;
  suitePolicy: ExecutionPolicy;
}

const ItemExecutionPolicySection: React.FC<ItemExecutionPolicySectionProps> = ({
  itemId,
  suitePolicy,
}) => {
  const itemPolicy = useItemExecutionPolicy(itemId);
  const setItemExecutionPolicy = useSetItemExecutionPolicy();

  const isOverridden = itemPolicy !== null;
  const currentPolicy = itemPolicy ?? suitePolicy;

  const handleToggle = useCallback(
    (checked: boolean) => {
      if (checked) {
        setItemExecutionPolicy(itemId, { ...suitePolicy });
      } else {
        setItemExecutionPolicy(itemId, null);
      }
    },
    [itemId, suitePolicy, setItemExecutionPolicy],
  );

  const handleRunsChange = useCallback(
    (value: string) => {
      const runs = Math.max(1, parseInt(value, 10) || 1);
      setItemExecutionPolicy(itemId, {
        runs_per_item: runs,
        pass_threshold: Math.min(currentPolicy.pass_threshold, runs),
      });
    },
    [itemId, currentPolicy, setItemExecutionPolicy],
  );

  const handleThresholdChange = useCallback(
    (value: string) => {
      const threshold = Math.max(1, parseInt(value, 10) || 1);
      setItemExecutionPolicy(itemId, {
        ...currentPolicy,
        pass_threshold: Math.min(threshold, currentPolicy.runs_per_item),
      });
    },
    [itemId, currentPolicy, setItemExecutionPolicy],
  );

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Execution policy</h3>
      <p className="comet-body-xs mb-3 text-muted-slate">
        Suite default: {suitePolicy.runs_per_item} run
        {suitePolicy.runs_per_item !== 1 ? "s" : ""},{" "}
        {suitePolicy.pass_threshold} to pass
      </p>

      <div className="mb-3 flex items-center gap-2">
        <Switch
          id={`override-policy-${itemId}`}
          checked={isOverridden}
          onCheckedChange={handleToggle}
        />
        <Label htmlFor={`override-policy-${itemId}`}>
          Override suite default
        </Label>
      </div>

      {isOverridden && (
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <Label htmlFor={`item-runs-${itemId}`}>Runs per item</Label>
            <Input
              id={`item-runs-${itemId}`}
              type="number"
              min={1}
              value={currentPolicy.runs_per_item}
              onChange={(e) => handleRunsChange(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`item-threshold-${itemId}`}>Pass threshold</Label>
            <Input
              id={`item-threshold-${itemId}`}
              type="number"
              min={1}
              max={currentPolicy.runs_per_item}
              value={currentPolicy.pass_threshold}
              onChange={(e) => handleThresholdChange(e.target.value)}
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default ItemExecutionPolicySection;
