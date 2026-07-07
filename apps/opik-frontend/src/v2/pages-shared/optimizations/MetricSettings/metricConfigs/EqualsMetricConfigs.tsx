import React from "react";
import { Label } from "@/ui/label";
import { Checkbox } from "@/ui/checkbox";
import {
  EqualsMetricParameters,
  MetricParamErrors,
} from "@/types/optimizations";
import ReferenceKeyField from "../ReferenceKeyField";

interface EqualsMetricConfigsProps {
  configs: Partial<EqualsMetricParameters>;
  onChange: (configs: Partial<EqualsMetricParameters>) => void;
  datasetVariables?: string[];
  errors?: MetricParamErrors;
}

const EqualsMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
  errors,
}: EqualsMetricConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-4">
        <ReferenceKeyField
          value={configs.reference_key ?? ""}
          onChange={(value) => onChange({ ...configs, reference_key: value })}
          datasetVariables={datasetVariables}
          error={errors?.reference_key?.message}
        />

        <div className="flex items-center space-x-2">
          <Checkbox
            id="case_sensitive"
            checked={configs.case_sensitive}
            onCheckedChange={(checked) =>
              onChange({ ...configs, case_sensitive: checked === true })
            }
          />
          <Label htmlFor="case_sensitive" className="cursor-pointer text-sm">
            Case sensitive comparison
          </Label>
        </div>
      </div>
    </div>
  );
};

export default EqualsMetricConfigs;
