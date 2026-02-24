import { useCallback } from "react";
import { Settings2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ExecutionPolicy, MAX_RUNS_PER_ITEM } from "@/types/evaluation-suites";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import { cn } from "@/lib/utils";

interface ExecutionPolicyDropdownProps {
  policy: ExecutionPolicy;
  onChange: (policy: ExecutionPolicy) => void;
}

function ExecutionPolicyDropdown({
  policy,
  onChange,
}: ExecutionPolicyDropdownProps): React.ReactElement {
  const onRunsCommit = useCallback(
    (runs: number) => {
      onChange({
        ...policy,
        runs_per_item: runs,
        pass_threshold: Math.min(policy.pass_threshold, runs),
      });
    },
    [policy, onChange],
  );

  const onThresholdCommit = useCallback(
    (threshold: number) => {
      onChange({
        ...policy,
        pass_threshold: threshold,
      });
    },
    [policy, onChange],
  );

  const runsInput = useClampedIntegerInput({
    value: policy.runs_per_item,
    min: 1,
    max: MAX_RUNS_PER_ITEM,
    onCommit: onRunsCommit,
  });

  const thresholdInput = useClampedIntegerInput({
    value: policy.pass_threshold,
    min: 1,
    max: policy.runs_per_item,
    onCommit: onThresholdCommit,
  });

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm">
          <Settings2 className="mr-1 size-4" />
          Execution policy
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        className="max-h-[70vh] overflow-y-auto p-6"
        side="bottom"
        align="start"
      >
        <div className="w-72">
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor="runs-per-item">Runs per item</Label>
              <Input
                id="runs-per-item"
                className={cn(
                  "[&::-webkit-inner-spin-button]:appearance-none",
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
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="pass-threshold">Pass threshold</Label>
              <Input
                id="pass-threshold"
                className={cn(
                  "[&::-webkit-inner-spin-button]:appearance-none",
                  {
                    "border-destructive": thresholdInput.isInvalid,
                  },
                )}
                type="number"
                min={1}
                max={policy.runs_per_item}
                value={thresholdInput.displayValue}
                onChange={thresholdInput.onChange}
                onFocus={thresholdInput.onFocus}
                onBlur={thresholdInput.onBlur}
              />
            </div>
          </div>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export default ExecutionPolicyDropdown;
