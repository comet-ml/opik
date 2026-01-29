import React, { useMemo } from "react";
import { FlaskConical, Copy, FileText } from "lucide-react";

import { Span, Trace } from "@/types/traces";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";

type PromptValue = {
  prompt_name: string;
  prompt: string;
};

type OpikConfigData = {
  experiment_id?: string | null;
  values?: Record<string, unknown>;
};

type ConfigurationTabProps = {
  data: Trace | Span;
  search?: string;
};

type ConfigRow = {
  key: string;
  value: unknown;
  type: "string" | "number" | "boolean" | "prompt";
};

const isPromptValue = (value: unknown): value is PromptValue => {
  return (
    typeof value === "object" &&
    value !== null &&
    "prompt_name" in value &&
    "prompt" in value
  );
};

const inferType = (value: unknown): ConfigRow["type"] => {
  if (typeof value === "boolean") return "boolean";
  if (typeof value === "number") return "number";
  if (isPromptValue(value)) return "prompt";
  return "string";
};

const ConfigurationTab: React.FunctionComponent<ConfigurationTabProps> = ({
  data,
}) => {
  const { toast } = useToast();

  const configData = useMemo<OpikConfigData | null>(() => {
    const metadata = data.metadata as Record<string, unknown> | undefined;
    if (!metadata?.opik_config) return null;
    return metadata.opik_config as OpikConfigData;
  }, [data.metadata]);

  const configRows = useMemo<ConfigRow[]>(() => {
    if (!configData?.values) return [];
    return Object.entries(configData.values).map(([key, value]) => ({
      key,
      value,
      type: inferType(value),
    }));
  }, [configData?.values]);

  const handleCopyExperimentId = () => {
    if (configData?.experiment_id) {
      navigator.clipboard.writeText(configData.experiment_id);
      toast({ description: "Experiment ID copied" });
    }
  };

  if (!configData) {
    return (
      <div className="py-6 text-center text-muted-slate">
        <p className="text-sm">No configuration data for this trace.</p>
        <p className="mt-1 text-xs">
          Use the @agent_config decorator to capture config values.
        </p>
      </div>
    );
  }

  const hasExperiment = Boolean(configData.experiment_id);

  return (
    <div className="space-y-4">
      {hasExperiment && (
        <div className="flex items-center justify-between rounded-md border bg-purple-50 p-3 dark:bg-purple-950/30">
          <div className="flex items-center gap-2">
            <FlaskConical className="size-4 text-purple-500" />
            <span className="text-sm font-medium">Experiment</span>
            <code className="rounded bg-background px-2 py-0.5 font-mono text-sm">
              {configData.experiment_id}
            </code>
          </div>
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={handleCopyExperimentId}
          >
            <Copy className="size-4" />
          </Button>
        </div>
      )}

      {configRows.length > 0 && (
        <div className="rounded-md border">
          <table className="w-full">
            <thead>
              <tr className="border-b bg-muted/30">
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-slate">
                  Key
                </th>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-slate">
                  Value
                </th>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-slate">
                  Type
                </th>
              </tr>
            </thead>
            <tbody>
              {configRows.map((row) => (
                <tr key={row.key} className="border-b last:border-b-0">
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-2">
                      {row.type === "prompt" && (
                        <FileText className="size-4 shrink-0 text-muted-slate" />
                      )}
                      <code className="font-mono text-sm">{row.key}</code>
                    </div>
                  </td>
                  <td className="px-3 py-2">
                    {row.type === "prompt" ? (
                      <span className="font-mono text-sm">
                        {(row.value as PromptValue).prompt_name}
                      </span>
                    ) : row.type === "boolean" ? (
                      <Tag
                        variant={row.value ? "green" : "gray"}
                        size="sm"
                      >
                        {String(row.value)}
                      </Tag>
                    ) : (
                      <span className="font-mono text-sm">
                        {String(row.value)}
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <Tag variant="gray" size="sm" className="capitalize">
                      {row.type}
                    </Tag>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {configRows.length === 0 && !hasExperiment && (
        <div className="py-6 text-center text-muted-slate">
          <p className="text-sm">No configuration values recorded.</p>
        </div>
      )}
    </div>
  );
};

export default ConfigurationTab;
