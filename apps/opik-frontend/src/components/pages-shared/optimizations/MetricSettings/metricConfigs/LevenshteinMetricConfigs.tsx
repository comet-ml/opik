import React from "react";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { LevenshteinMetricParameters } from "@/types/optimizations";
import { DEFAULT_LEVENSHTEIN_METRIC_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

interface LevenshteinMetricConfigsProps {
  configs: Partial<LevenshteinMetricParameters>;
  onChange: (configs: Partial<LevenshteinMetricParameters>) => void;
}

const LevenshteinMetricConfigs = ({
  configs,
  onChange,
}: LevenshteinMetricConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-4">
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Label htmlFor="reference_key" className="text-sm">
              Reference key
            </Label>
            <ExplainerIcon description="The key in the dataset item to compare against" />
          </div>
          <Input
            id="reference_key"
            placeholder="e.g., answer"
            value={configs.reference_key}
            onChange={(e) =>
              onChange({ ...configs, reference_key: e.target.value })
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
            Case sensitive
          </Label>
          <ExplainerIcon description="Enable case-sensitive comparison when evaluating outputs" />
        </div>
      </div>
    </div>
  );
};

export default LevenshteinMetricConfigs;
