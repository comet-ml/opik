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
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";

interface AlgorithmConfigsProps {
  optimizerType: OPTIMIZER_TYPE;
  configs: Partial<OptimizerParameters>;
  onChange: (configs: Partial<OptimizerParameters>) => void;
  size?: ButtonProps["size"];
  variant?: ButtonProps["variant"];
  className?: string;
  disabled?: boolean;
}

const AlgorithmConfigs = ({
  optimizerType,
  configs,
  onChange,
  size = "icon-sm",
  variant = "outline",
  className,
  disabled: disabledProp = false,
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
        className="max-h-[70vh] overflow-y-auto p-6"
        side="bottom"
        align="end"
      >
        <div className="mb-5 w-72">
          <div className="mb-1 flex items-center gap-1">
            <h3 className="comet-body-s-accented">Algorithm settings</h3>
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_algorithm_settings]}
            />
          </div>
          <p className="comet-body-xs text-muted-slate">
            Configure parameters for the selected optimization algorithm
          </p>
        </div>
        <div className="mb-6 flex w-72 flex-col gap-2">
          <div className="flex items-center justify-between">
            <Label className="text-sm">Algorithm model</Label>
            {configs.model && (
              <Button
                variant="link"
                size="sm"
                className="h-auto p-0"
                onClick={() => {
                  const next = { ...configs };
                  delete next.model;
                  delete next.model_parameters;
                  onChange(next);
                }}
              >
                Use prompt model
              </Button>
            )}
          </div>
          <OptimizationModelSelect
            value={(configs.model ?? "") as PROVIDER_MODEL_TYPE | ""}
            onChange={(value) => onChange({ ...configs, model: value })}
          />
          <p className="comet-body-xs text-muted-slate">
            The model the optimizer uses for its own reasoning. Defaults to the
            prompt model.
          </p>
        </div>
        {getOptimizerForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default AlgorithmConfigs;
