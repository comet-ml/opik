import React from "react";
import { Settings2 } from "lucide-react";
import isEmpty from "lodash/isEmpty";
import {
  METRIC_TYPE,
  MetricParameters,
  EqualsMetricParameters,
  JsonSchemaValidatorMetricParameters,
  GEvalMetricParameters,
} from "@/types/optimizations";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button, ButtonProps } from "@/components/ui/button";
import EqualsMetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/metricConfigs/EqualsMetricConfigs";
import JsonSchemaValidatorMetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/metricConfigs/JsonSchemaValidatorMetricConfigs";
import GEvalMetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/metricConfigs/GEvalMetricConfigs";

interface MetricConfigsProps {
  metricType: METRIC_TYPE;
  configs: Partial<MetricParameters>;
  onChange: (configs: Partial<MetricParameters>) => void;
  size?: ButtonProps["size"];
  disabled?: boolean;
}

const MetricConfigs = ({
  metricType,
  configs,
  onChange,
  size = "icon-sm",
  disabled: disabledProp = false,
}: MetricConfigsProps) => {
  const getMetricForm = () => {
    if (metricType === METRIC_TYPE.EQUALS) {
      return (
        <EqualsMetricConfigs
          configs={configs as Partial<EqualsMetricParameters>}
          onChange={onChange}
        />
      );
    }

    if (metricType === METRIC_TYPE.JSON_SCHEMA_VALIDATOR) {
      return (
        <JsonSchemaValidatorMetricConfigs
          configs={configs as Partial<JsonSchemaValidatorMetricParameters>}
          onChange={onChange}
        />
      );
    }

    if (metricType === METRIC_TYPE.G_EVAL) {
      return (
        <GEvalMetricConfigs
          configs={configs as Partial<GEvalMetricParameters>}
          onChange={onChange}
        />
      );
    }

    return null;
  };

  const disabled = disabledProp || !metricType || isEmpty(configs);

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
          <h3 className="comet-body-s-accented mb-1">Metric settings</h3>
          <p className="comet-body-xs text-muted-slate">
            Configure parameters for the selected evaluation metric
          </p>
        </div>
        {getMetricForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default MetricConfigs;
