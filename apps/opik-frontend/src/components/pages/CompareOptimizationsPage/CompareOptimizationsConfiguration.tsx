import React from "react";
import { Link } from "@tanstack/react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { OptimizationStudioConfig } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import { MessagesList } from "@/components/pages-shared/prompts/PromptMessageDisplay";
import { OPTIMIZATION_METRIC_OPTIONS } from "@/constants/optimizations";
import { getOptimizerLabel } from "@/lib/optimizations";
import { extractDisplayMessages } from "@/lib/llm";
import useAppStore from "@/store/AppStore";

interface CompareOptimizationsConfigurationProps {
  studioConfig: OptimizationStudioConfig;
  datasetId: string;
  optimizationId: string;
  bestExperiment?: Experiment;
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
> = ({ studioConfig, datasetId, optimizationId, bestExperiment }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { prompt, optimizer, evaluation, dataset_name, llm_model } =
    studioConfig;
  const metric = evaluation?.metrics?.[0];

  const messages = extractDisplayMessages(prompt?.messages);

  return (
    <Card className="flex size-full flex-col">
      <CardHeader className="shrink-0 pb-2">
        <CardTitle className="text-sm">Configuration</CardTitle>
      </CardHeader>
      <CardContent className="flex shrink-0 flex-col gap-1">
        <ConfigItem
          label="Dataset"
          value={
            <Link
              to="/$workspaceName/datasets/$datasetId"
              params={{ workspaceName, datasetId }}
              className="text-primary hover:underline"
              target="_blank"
              rel="noopener noreferrer"
            >
              {dataset_name}
            </Link>
          }
        />
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
        {bestExperiment && (
          <ConfigItem
            label="Best trial configuration"
            value={
              <Link
                to="/$workspaceName/optimizations/$datasetId/$optimizationId/compare"
                params={{
                  workspaceName,
                  datasetId,
                  optimizationId,
                }}
                target="_blank"
                rel="noopener noreferrer"
                search={{ trials: [bestExperiment.id], tab: "config" }}
                className="text-primary hover:underline"
              >
                {bestExperiment.name}
              </Link>
            }
          />
        )}
      </CardContent>

      <Separator className="my-2 shrink-0" />

      <CardHeader className="shrink-0 px-6 py-1.5">
        <CardTitle className="text-sm">Initial prompt</CardTitle>
      </CardHeader>
      <CardContent className="min-h-0 flex-1 overflow-auto">
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
