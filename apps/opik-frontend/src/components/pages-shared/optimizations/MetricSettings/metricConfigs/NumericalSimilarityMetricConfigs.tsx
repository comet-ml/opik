import React from "react";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { NumericalSimilarityMetricParameters } from "@/types/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import DatasetVariablesHint from "../DatasetVariablesHint";

interface NumericalSimilarityMetricConfigsProps {
  configs: Partial<NumericalSimilarityMetricParameters>;
  onChange: (configs: Partial<NumericalSimilarityMetricParameters>) => void;
  datasetVariables?: string[];
}

const NumericalSimilarityMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
}: NumericalSimilarityMetricConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-4">
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Label htmlFor="reference_key" className="text-sm">
              Reference key
            </Label>
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.metric_reference_key]}
            />
          </div>
          <Input
            id="reference_key"
            placeholder="e.g., score or $.feedback_scores[?(@.name=='Useful')].value"
            value={configs.reference_key ?? ""}
            onChange={(e) =>
              onChange({ ...configs, reference_key: e.target.value })
            }
          />
          <DatasetVariablesHint
            datasetVariables={datasetVariables}
            onSelect={(variable) =>
              onChange({ ...configs, reference_key: variable })
            }
          />
        </div>
      </div>
    </div>
  );
};

export default NumericalSimilarityMetricConfigs;
