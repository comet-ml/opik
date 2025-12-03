import React, { useState } from "react";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { JsonSchemaValidatorMetricParameters } from "@/types/optimizations";
import { DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface JsonSchemaValidatorMetricConfigsProps {
  configs: Partial<JsonSchemaValidatorMetricParameters>;
  onChange: (configs: Partial<JsonSchemaValidatorMetricParameters>) => void;
}

const JsonSchemaValidatorMetricConfigs = ({
  configs,
  onChange,
}: JsonSchemaValidatorMetricConfigsProps) => {
  const [schemaText, setSchemaText] = useState(
    JSON.stringify(
      configs.schema ?? DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.SCHEMA,
      null,
      2,
    ),
  );
  const [error, setError] = useState<string>("");

  const handleSchemaChange = (value: string) => {
    setSchemaText(value);
    setError("");

    if (!value.trim()) {
      onChange({ ...configs, schema: {} });
      return;
    }

    try {
      const parsed = JSON.parse(value);
      onChange({ ...configs, schema: parsed });
    } catch (e) {
      setError("Invalid JSON format");
    }
  };

  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="json_schema" className="text-sm mr-1.5">
            JSON Schema
          </Label>
          <ExplainerIcon {...EXPLAINERS_MAP[EXPLAINER_ID.metric_json_schema]} />
        </div>
        <Textarea
          id="json_schema"
          value={schemaText}
          onChange={(e) => handleSchemaChange(e.target.value)}
          placeholder='{"type": "object", "properties": {...}}'
          className="font-mono text-xs"
          rows={10}
        />
        {error && <p className="text-xs text-warning">{error}</p>}
      </div>
    </div>
  );
};

export default JsonSchemaValidatorMetricConfigs;
