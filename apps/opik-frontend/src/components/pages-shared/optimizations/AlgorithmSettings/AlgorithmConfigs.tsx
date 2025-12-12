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
} from "@/components/ui/dropdown-menu";
import { Button, ButtonProps } from "@/components/ui/button";
import GepaOptimizerConfigs from "@/components/pages-shared/optimizations/AlgorithmSettings/algorithmConfigs/GepaOptimizerConfigs";
import EvolutionaryOptimizerConfigs from "@/components/pages-shared/optimizations/AlgorithmSettings/algorithmConfigs/EvolutionaryOptimizerConfigs";
import HierarchicalReflectiveOptimizerConfigs from "@/components/pages-shared/optimizations/AlgorithmSettings/algorithmConfigs/HierarchicalReflectiveOptimizerConfigs";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface AlgorithmConfigsProps {
  optimizerType: OPTIMIZER_TYPE;
  configs: Partial<OptimizerParameters>;
  onChange: (configs: Partial<OptimizerParameters>) => void;
  size?: ButtonProps["size"];
  disabled?: boolean;
}

const AlgorithmConfigs = ({
  optimizerType,
  configs,
  onChange,
  size = "icon-sm",
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
        <Button variant="outline" size={size} disabled={disabled}>
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
        {getOptimizerForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default AlgorithmConfigs;
