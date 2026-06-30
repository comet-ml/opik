import React from "react";
import { NumericalSimilarityMetricParameters } from "@/types/optimizations";
import ReferenceKeyField from "../ReferenceKeyField";

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
        <ReferenceKeyField
          value={configs.reference_key ?? ""}
          onChange={(value) => onChange({ ...configs, reference_key: value })}
          datasetVariables={datasetVariables}
          placeholder="e.g., score or $.feedback_scores[?(@.name=='Useful')].value"
        />
      </div>
    </div>
  );
};

export default NumericalSimilarityMetricConfigs;
