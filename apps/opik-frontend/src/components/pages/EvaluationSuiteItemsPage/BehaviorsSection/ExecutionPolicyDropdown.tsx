import { Settings2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ExecutionPolicy } from "@/types/evaluation-suites";

interface ExecutionPolicyDropdownProps {
  policy: ExecutionPolicy;
  onChange: (policy: ExecutionPolicy) => void;
}

const ExecutionPolicyDropdown: React.FC<ExecutionPolicyDropdownProps> = ({
  policy,
  onChange,
}) => {
  const handleRunsChange = (value: string) => {
    const runs = Math.max(1, parseInt(value, 10) || 1);
    onChange({
      ...policy,
      runs_per_item: runs,
      pass_threshold: Math.min(policy.pass_threshold, runs),
    });
  };

  const handleThresholdChange = (value: string) => {
    const threshold = Math.max(1, parseInt(value, 10) || 1);
    onChange({
      ...policy,
      pass_threshold: Math.min(threshold, policy.runs_per_item),
    });
  };

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
        align="end"
      >
        <div className="w-72">
          <div className="mb-4">
            <h3 className="comet-body-s-accented">Execution policy</h3>
            <p className="comet-body-xs text-muted-slate">
              Configure how many times each item is evaluated and the pass
              threshold
            </p>
          </div>
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor="runs-per-item">Runs per item</Label>
              <Input
                id="runs-per-item"
                type="number"
                min={1}
                value={policy.runs_per_item}
                onChange={(e) => handleRunsChange(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="pass-threshold">Pass threshold</Label>
              <Input
                id="pass-threshold"
                type="number"
                min={1}
                max={policy.runs_per_item}
                value={policy.pass_threshold}
                onChange={(e) => handleThresholdChange(e.target.value)}
              />
            </div>
          </div>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ExecutionPolicyDropdown;
