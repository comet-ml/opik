import React from "react";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { HierarchicalReflectiveOptimizerParameters } from "@/types/optimizations";
import { DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface HierarchicalReflectiveOptimizerConfigsProps {
  configs: Partial<HierarchicalReflectiveOptimizerParameters>;
  onChange: (
    configs: Partial<HierarchicalReflectiveOptimizerParameters>,
  ) => void;
}

const HierarchicalReflectiveOptimizerConfigs = ({
  configs,
  onChange,
}: HierarchicalReflectiveOptimizerConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <SliderInputControl
        value={
          configs.convergence_threshold ??
          DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD
        }
        onChange={(v) => onChange({ ...configs, convergence_threshold: v })}
        id="convergence_threshold"
        min={0}
        max={1}
        step={0.001}
        defaultValue={
          DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD
        }
        label="Convergence threshold"
        tooltip="Threshold for determining when optimization has converged. Lower values require more precision (0-1)"
      />

      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="verbose"
            checked={
              configs.verbose ??
              DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.VERBOSE
            }
            onCheckedChange={(checked) =>
              onChange({ ...configs, verbose: checked === true })
            }
          />
          <Label htmlFor="verbose" className="cursor-pointer text-sm">
            Verbose
          </Label>
          <ExplainerIcon {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_verbose]} />
        </div>
      </div>

      <SliderInputControl
        value={
          configs.seed ?? DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED
        }
        onChange={(v) => onChange({ ...configs, seed: v })}
        id="seed"
        min={0}
        max={1000}
        step={1}
        defaultValue={DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED}
        label="Seed"
        tooltip="Random seed for reproducibility. Use the same seed to get consistent results across runs."
      />
    </div>
  );
};

export default HierarchicalReflectiveOptimizerConfigs;
