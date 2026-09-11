import React from "react";
import { Label } from "@/ui/label";
import { Switch } from "@/ui/switch";
import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { GepaOptimizerParameters } from "@/types/optimizations";
import { DEFAULT_GEPA_OPTIMIZER_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";

interface GepaOptimizerConfigsProps {
  configs: Partial<GepaOptimizerParameters>;
  onChange: (configs: Partial<GepaOptimizerParameters>) => void;
}

const GepaOptimizerConfigs = ({
  configs,
  onChange,
}: GepaOptimizerConfigsProps) => {
  // Fragment (no wrapper): fields render as direct siblings of the popover's
  // column so the algorithm model, Verbose and Seed all share one gap.
  return (
    <>
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
          checked={configs.verbose ?? DEFAULT_GEPA_OPTIMIZER_CONFIGS.VERBOSE}
          onCheckedChange={(checked) =>
            onChange({ ...configs, verbose: checked })
          }
        />
      </div>

      <SliderInputControl
        value={configs.seed ?? DEFAULT_GEPA_OPTIMIZER_CONFIGS.SEED}
        onChange={(v) => onChange({ ...configs, seed: v })}
        id="seed"
        min={0}
        max={1000}
        step={1}
        defaultValue={DEFAULT_GEPA_OPTIMIZER_CONFIGS.SEED}
        label="Seed"
        tooltip="Random seed for reproducibility. Use the same seed to get consistent results across runs."
      />
    </>
  );
};

export default GepaOptimizerConfigs;
