import React from "react";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { GepaOptimizerParameters } from "@/types/optimizations";
import { DEFAULT_GEPA_OPTIMIZER_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface GepaOptimizerConfigsProps {
  configs: Partial<GepaOptimizerParameters>;
  onChange: (configs: Partial<GepaOptimizerParameters>) => void;
}

const GepaOptimizerConfigs = ({
  configs,
  onChange,
}: GepaOptimizerConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="verbose"
            checked={configs.verbose ?? DEFAULT_GEPA_OPTIMIZER_CONFIGS.VERBOSE}
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
    </div>
  );
};

export default GepaOptimizerConfigs;
