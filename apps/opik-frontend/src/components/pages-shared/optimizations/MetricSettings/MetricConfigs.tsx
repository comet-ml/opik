import React from "react";
import { Settings2 } from "lucide-react";
import isEmpty from "lodash/isEmpty";
import {
  METRIC_TYPE,
  MetricParameters,
  EqualsMetricParameters,
  JsonSchemaValidatorMetricParameters,
  GEvalMetricParameters,
  LevenshteinMetricParameters,
  CodeMetricParameters,
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
import LevenshteinMetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/metricConfigs/LevenshteinMetricConfigs";
import CodeMetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/metricConfigs/CodeMetricConfigs";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface MetricConfigsProps {
  metricType: METRIC_TYPE;
  configs: Partial<MetricParameters>;
  onChange: (configs: Partial<MetricParameters>) => void;
  size?: ButtonProps["size"];
  disabled?: boolean;
  inline?: boolean;
  datasetVariables?: string[];
}

const MetricConfigs = ({
  metricType,
  configs,
  onChange,
  size = "icon-sm",
  disabled: disabledProp = false,
  inline = false,
  datasetVariables = [],
}: MetricConfigsProps) => {
  const getMetricForm = () => {
    if (metricType === METRIC_TYPE.EQUALS) {
      return (
        <EqualsMetricConfigs
          configs={configs as Partial<EqualsMetricParameters>}
          onChange={onChange}
          datasetVariables={datasetVariables}
        />
      );
    }

    if (metricType === METRIC_TYPE.JSON_SCHEMA_VALIDATOR) {
      return (
        <JsonSchemaValidatorMetricConfigs
          configs={configs as Partial<JsonSchemaValidatorMetricParameters>}
          onChange={onChange}
          datasetVariables={datasetVariables}
        />
      );
    }

    if (metricType === METRIC_TYPE.G_EVAL) {
      return (
        <GEvalMetricConfigs
          configs={configs as Partial<GEvalMetricParameters>}
          onChange={onChange}
          datasetVariables={datasetVariables}
        />
      );
    }

    if (metricType === METRIC_TYPE.LEVENSHTEIN) {
      return (
        <LevenshteinMetricConfigs
          configs={configs as Partial<LevenshteinMetricParameters>}
          onChange={onChange}
          datasetVariables={datasetVariables}
        />
      );
    }

    if (metricType === METRIC_TYPE.CODE) {
      return (
        <CodeMetricConfigs
          configs={configs as Partial<CodeMetricParameters>}
          onChange={onChange}
        />
      );
    }

    return null;
  };

  const disabled = disabledProp || !metricType || isEmpty(configs);

  // Inline mode - render form directly without dropdown
  if (inline) {
    return <div className="w-full [&>div]:w-full">{getMetricForm()}</div>;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size={size} disabled={disabled}>
          <Settings2 />
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent
        className="max-h-[50vh] overflow-y-auto p-6"
        side="bottom"
        align="end"
      >
        <div className="mb-5 w-72">
          <div className="mb-1 flex items-center gap-1">
            <h3 className="comet-body-s-accented">Metric settings</h3>
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_metric_settings]}
            />
          </div>
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
