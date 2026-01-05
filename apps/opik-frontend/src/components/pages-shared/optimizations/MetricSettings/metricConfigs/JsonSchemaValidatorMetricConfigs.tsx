import React from "react";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { JsonSchemaValidatorMetricParameters } from "@/types/optimizations";
import { DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import DatasetVariablesHint from "../DatasetVariablesHint";

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
  const referenceKey =
    configs.reference_key ??
    DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.REFERENCE_KEY;

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
            value={referenceKey}
            onChange={(e) =>
              onChange({ ...configs, reference_key: e.target.value })
            }
            placeholder="e.g., expected_output"
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

export default JsonSchemaValidatorMetricConfigs;
