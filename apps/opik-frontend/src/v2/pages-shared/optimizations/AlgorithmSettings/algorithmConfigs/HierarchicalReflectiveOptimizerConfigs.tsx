import React from "react";
import { Label } from "@/ui/label";
import { Switch } from "@/ui/switch";
import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { HierarchicalReflectiveOptimizerParameters } from "@/types/optimizations";
import { DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";

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
  // Fragment (no wrapper): fields render as direct siblings of the popover's
  // column so every field shares one consistent gap.
  return (
    <>
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

      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1">
          <Label htmlFor="verbose" className="cursor-pointer text-sm">
            Verbose
          </Label>
          <ExplainerIcon {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_verbose]} />
        </div>
        <Switch
          id="verbose"
          size="sm"
          checked={
            configs.verbose ??
            DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.VERBOSE
          }
          onCheckedChange={(checked) =>
            onChange({ ...configs, verbose: checked })
          }
        />
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
    </>
  );
};

export default HierarchicalReflectiveOptimizerConfigs;
