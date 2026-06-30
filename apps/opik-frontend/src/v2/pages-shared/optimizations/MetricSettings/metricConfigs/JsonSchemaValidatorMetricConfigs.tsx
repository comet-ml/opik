import React from "react";
import { JsonSchemaValidatorMetricParameters } from "@/types/optimizations";
import { DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS } from "@/constants/optimizations";
import ReferenceKeyField from "../ReferenceKeyField";

interface JsonSchemaValidatorMetricConfigsProps {
  configs: Partial<JsonSchemaValidatorMetricParameters>;
  onChange: (configs: Partial<JsonSchemaValidatorMetricParameters>) => void;
  datasetVariables?: string[];
}

const JsonSchemaValidatorMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
}: JsonSchemaValidatorMetricConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-4">
        <ReferenceKeyField
          value={
            configs.reference_key ??
            DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.REFERENCE_KEY
          }
          onChange={(value) => onChange({ ...configs, reference_key: value })}
          datasetVariables={datasetVariables}
          placeholder="e.g., expected_output"
        />
      </div>
    </div>
  );
};

export default JsonSchemaValidatorMetricConfigs;
