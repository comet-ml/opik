import React from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { OptimizationStudioConfig } from "@/types/optimizations";
import { MessagesList } from "@/components/pages-shared/prompts/PromptMessageDisplay";
import { OPTIMIZATION_METRIC_OPTIONS } from "@/constants/optimizations";
import { getOptimizerLabel } from "@/lib/optimizations";

interface CompareOptimizationsConfigurationProps {
  studioConfig: OptimizationStudioConfig;
}

const getMetricLabel = (type: string): string => {
  return (
    OPTIMIZATION_METRIC_OPTIONS.find((opt) => opt.value === type)?.label || type
  );
};

const formatParamName = (key: string): string => {
  return key.replace(/_/g, " ").replace(/\b\w/g, (char) => char.toUpperCase());
};

const ConfigItem: React.FC<{ label: string; value: React.ReactNode }> = ({
  label,
  value,
}) => (
  <div className="flex items-baseline gap-2">
    <span className="comet-body-xs text-muted-slate">{label}:</span>
    <span className="comet-body-xs">{value}</span>
  </div>
);

const CompareOptimizationsConfiguration: React.FC<
  CompareOptimizationsConfigurationProps
> = ({ studioConfig }) => {
  const { prompt, optimizer, evaluation, dataset_name, llm_model } =
    studioConfig;
  const metric = evaluation?.metrics?.[0];

  const messages = prompt?.messages?.map((msg) => ({
    role: msg.role,
    content: msg.content,
  }));

  return (
    <Card className="w-full">
      <CardHeader className="pb-2">
        <CardTitle className="text-sm">Configuration</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        <ConfigItem label="Dataset" value={dataset_name} />
        <ConfigItem label="Model" value={llm_model?.model || "-"} />
        <ConfigItem
          label="Algorithm"
          value={optimizer?.type ? getOptimizerLabel(optimizer.type) : "-"}
        />
        <ConfigItem
          label="Metric"
          value={metric?.type ? getMetricLabel(metric.type) : "-"}
        />
        {metric?.parameters && Object.keys(metric.parameters).length > 0 && (
          <div className="ml-4 flex flex-col gap-1">
            {Object.entries(metric.parameters).map(([key, value]) => (
              <ConfigItem
                key={key}
                label={formatParamName(key)}
                value={String(value)}
              />
            ))}
          </div>
        )}
      </CardContent>

      <Separator className="my-2" />

      <CardHeader className="p-2">
        <CardTitle className="text-sm">Initial prompt</CardTitle>
      </CardHeader>
      <CardContent>
        {messages && messages.length > 0 ? (
          <MessagesList messages={messages} />
        ) : (
          <span className="comet-body-s text-muted-slate">
            No prompt messages
          </span>
        )}
      </CardContent>
    </Card>
  );
};

export default CompareOptimizationsConfiguration;
