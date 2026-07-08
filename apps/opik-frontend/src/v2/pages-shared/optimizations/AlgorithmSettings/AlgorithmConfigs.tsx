import React from "react";
import { Settings2 } from "lucide-react";
import isEmpty from "lodash/isEmpty";
import {
  OPTIMIZER_TYPE,
  OptimizerParameters,
  GepaOptimizerParameters,
  EvolutionaryOptimizerParameters,
  HierarchicalReflectiveOptimizerParameters,
} from "@/types/optimizations";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button, ButtonProps } from "@/ui/button";
import { Label } from "@/ui/label";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import OptimizationModelSelect from "@/v2/pages-shared/optimizations/OptimizationModelSelect/OptimizationModelSelect";
import GepaOptimizerConfigs from "@/v2/pages-shared/optimizations/AlgorithmSettings/algorithmConfigs/GepaOptimizerConfigs";
import EvolutionaryOptimizerConfigs from "@/v2/pages-shared/optimizations/AlgorithmSettings/algorithmConfigs/EvolutionaryOptimizerConfigs";
import HierarchicalReflectiveOptimizerConfigs from "@/v2/pages-shared/optimizations/AlgorithmSettings/algorithmConfigs/HierarchicalReflectiveOptimizerConfigs";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";

interface AlgorithmConfigsProps {
  optimizerType: OPTIMIZER_TYPE;
  configs: Partial<OptimizerParameters>;
  onChange: (configs: Partial<OptimizerParameters>) => void;
  size?: ButtonProps["size"];
  variant?: ButtonProps["variant"];
  className?: string;
  disabled?: boolean;
  // The prompt model, shown as the effective default when no algorithm model
  // is explicitly set (the algorithm model defaults to the prompt model).
  promptModel?: string;
}

const AlgorithmConfigs = ({
  optimizerType,
  configs,
  onChange,
  size = "icon-sm",
  variant = "outline",
  className,
  disabled: disabledProp = false,
  promptModel,
}: AlgorithmConfigsProps) => {
  const getOptimizerForm = () => {
    if (optimizerType === OPTIMIZER_TYPE.GEPA) {
      return (
        <GepaOptimizerConfigs
          configs={configs as Partial<GepaOptimizerParameters>}
          onChange={onChange}
        />
      );
    }

    if (optimizerType === OPTIMIZER_TYPE.EVOLUTIONARY) {
      return (
        <EvolutionaryOptimizerConfigs
          configs={configs as Partial<EvolutionaryOptimizerParameters>}
          onChange={onChange}
        />
      );
    }

    if (optimizerType === OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE) {
      return (
        <HierarchicalReflectiveOptimizerConfigs
          configs={
            configs as Partial<HierarchicalReflectiveOptimizerParameters>
          }
          onChange={onChange}
        />
      );
    }

    return null;
  };

  const disabled = disabledProp || !optimizerType || isEmpty(configs);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant={variant}
          size={size}
          className={className}
          disabled={disabled}
        >
          <Settings2 />
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent
        className="max-h-[70vh] overflow-y-auto p-4"
        side="bottom"
        align="end"
      >
        {/* Single column with one gap so every field (model, then the
            optimizer's own fields) is spaced identically. */}
        <div className="flex w-72 flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <div className="flex items-center gap-1">
              <Label className="text-sm">Algorithm model</Label>
              <ExplainerIcon description="The model the optimizer uses for its own reasoning. Defaults to the prompt model." />
            </div>
            <OptimizationModelSelect
              compact
              value={(configs.model ?? "") as PROVIDER_MODEL_TYPE | ""}
              inheritedModel={(promptModel ?? "") as PROVIDER_MODEL_TYPE | ""}
              onChange={(value) => onChange({ ...configs, model: value })}
              onClear={() => {
                // Clear the explicit model so the optimizer inherits the prompt
                // model at runtime (shown as "Same as prompt").
                const next = { ...configs };
                delete next.model;
                delete next.model_parameters;
                onChange(next);
              }}
            />
          </div>
          {getOptimizerForm()}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default AlgorithmConfigs;
