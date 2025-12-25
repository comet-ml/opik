import React from "react";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { EqualsMetricParameters } from "@/types/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import DatasetVariablesHint from "../DatasetVariablesHint";

interface EqualsMetricConfigsProps {
  configs: Partial<EqualsMetricParameters>;
  onChange: (configs: Partial<EqualsMetricParameters>) => void;
  datasetVariables?: string[];
}

const EqualsMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
}: EqualsMetricConfigsProps) => {
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
            placeholder="e.g., answer"
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
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.metric_case_sensitive]}
          />
        </div>
      </div>
    </div>
  );
};

export default EqualsMetricConfigs;
