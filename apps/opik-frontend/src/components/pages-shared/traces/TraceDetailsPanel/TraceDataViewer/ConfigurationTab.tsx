import React, { useEffect, useMemo, useState } from "react";
import { Copy, FileText, ChevronDown, ChevronRight } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";

import { Span, Trace } from "@/types/traces";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { getExperimentIconConfig } from "@/lib/experimentIcons";

const CONFIG_BACKEND_URL = "http://localhost:5050";

type PromptValue = {
  prompt_name: string;
  prompt: string;
};

type OpikConfigData = {
  experiment_id?: string | null;
  experiment_type?: string | null;
  assigned_variant?: string | null;
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

type DiffLineType = "addition" | "deletion" | "context";
type DiffLine = { type: DiffLineType; content: string };

const computeSimpleDiff = (oldText: string, newText: string): DiffLine[] => {
  const oldLines = oldText.split("\n");
  const newLines = newText.split("\n");
  const diff: DiffLine[] = [];

  const maxLen = Math.max(oldLines.length, newLines.length);
  for (let i = 0; i < maxLen; i++) {
    const oldLine = oldLines[i];
    const newLine = newLines[i];

    if (oldLine === newLine) {
      diff.push({ type: "context", content: oldLine ?? "" });
    } else {
      if (oldLine !== undefined) {
        diff.push({ type: "deletion", content: oldLine });
      }
      if (newLine !== undefined) {
        diff.push({ type: "addition", content: newLine });
      }
    }
  }
  return diff;
};

const InlineDiffView: React.FC<{ diff: DiffLine[] }> = ({ diff }) => {
  if (!diff || diff.length === 0) return null;

  return (
    <div className="overflow-x-auto rounded border bg-muted/30 font-mono text-xs">
      {diff.map((line, idx) => (
        <div
          key={idx}
          className={cn("flex px-2 py-0.5", {
            "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300":
              line.type === "addition",
            "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300":
              line.type === "deletion",
            "text-muted-slate": line.type === "context",
          })}
        >
          <span className="mr-2 w-3 shrink-0">
            {line.type === "addition" && "+"}
            {line.type === "deletion" && "-"}
          </span>
          <span className="whitespace-pre-wrap break-all">{line.content}</span>
        </div>
      ))}
    </div>
  );
};

type BaselineData = {
  values: Record<string, { value: unknown; original_value?: unknown }>;
};

const fetchBaseline = async (projectId: string): Promise<BaselineData> => {
  const res = await fetch(
    `${CONFIG_BACKEND_URL}/v1/config/published?project_id=${projectId}&env=prod`
  );
  if (!res.ok) throw new Error("Failed to fetch baseline");
  const data = await res.json();
  const values: BaselineData["values"] = {};
  for (const item of data.values || []) {
    // Store both full key and short key (without class prefix)
    values[item.key] = {
      value: item.value,
      original_value: item.original_value,
    };
    // Also store by short key (e.g., "Config.foo" -> "foo")
    const dotIndex = item.key.indexOf(".");
    if (dotIndex !== -1) {
      const shortKey = item.key.substring(dotIndex + 1);
      values[shortKey] = {
        value: item.value,
        original_value: item.original_value,
      };
    }
  }
  return { values };
};

type ExpandablePromptRowProps = {
  row: ConfigRow;
  baseline?: { value: unknown; original_value?: unknown };
  hasExperiment: boolean;
};

const ExpandablePromptRow: React.FC<ExpandablePromptRowProps> = ({
  row,
  baseline,
  hasExperiment,
}) => {
  const promptValue = row.value as PromptValue;
  const currentPromptText = promptValue.prompt;

  const baselinePromptText = useMemo(() => {
    if (!baseline) return null;
    // Use value directly (published production value)
    const baseVal = baseline.value;
    if (isPromptValue(baseVal)) return baseVal.prompt;
    if (typeof baseVal === "string") return baseVal;
    return null;
  }, [baseline]);

  // Show diff if this is an experiment AND we have baseline AND they differ
  const hasChanges =
    hasExperiment &&
    baselinePromptText !== null &&
    baselinePromptText !== currentPromptText;

  const [expanded, setExpanded] = useState(false);

  // Auto-expand when changes are detected
  useEffect(() => {
    if (hasChanges) {
      setExpanded(true);
    }
  }, [hasChanges]);

  const diff = useMemo(() => {
    if (!hasChanges || !baselinePromptText) return [];
    return computeSimpleDiff(baselinePromptText, currentPromptText);
  }, [hasChanges, baselinePromptText, currentPromptText]);

  return (
    <>
      <tr className="border-b last:border-b-0">
        <td className="px-3 py-2">
          <div className="flex items-center gap-2">
            <FileText className="size-4 shrink-0 text-muted-slate" />
            <code className="font-mono text-sm">{row.key}</code>
          </div>
        </td>
        <td className="px-3 py-2">
          <div className="flex items-center gap-1">
            <button
              onClick={() => setExpanded(!expanded)}
              className="flex items-center gap-1 text-left hover:text-foreground"
            >
              {expanded ? (
                <ChevronDown className="size-3 shrink-0 text-muted-slate" />
              ) : (
                <ChevronRight className="size-3 shrink-0 text-muted-slate" />
              )}
              <span className="font-mono text-sm">{promptValue.prompt_name}</span>
            </button>
            {hasChanges && (
              <Tag variant="purple" size="sm" className="ml-1">
                modified
              </Tag>
            )}
          </div>
        </td>
        <td className="px-3 py-2">
          <Tag variant="gray" size="sm" className="capitalize">
            {row.type}
          </Tag>
        </td>
      </tr>
      {expanded && (
        <tr className="border-b last:border-b-0 bg-muted/20">
          <td colSpan={3} className="px-3 py-3">
            {hasChanges ? (
              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-slate">
                  Changes from baseline:
                </div>
                <InlineDiffView diff={diff} />
              </div>
            ) : (
              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-slate">
                  Prompt content:
                </div>
                <pre className="whitespace-pre-wrap break-words rounded border bg-background p-3 font-mono text-xs text-muted-slate">
                  {currentPromptText}
                </pre>
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  );
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

  const hasExperiment = Boolean(configData?.experiment_id);
  const hasPrompts = configRows.some((row) => row.type === "prompt");

  const { data: baselineData } = useQuery({
    queryKey: ["config-baseline", "default"],
    queryFn: () => fetchBaseline("default"),
    enabled: hasExperiment && hasPrompts,
    staleTime: 30000,
  });

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

  const hasVariant = Boolean(configData.assigned_variant);
  const iconConfig = getExperimentIconConfig(
    configData.experiment_type,
    configData.assigned_variant
  );
  const ExperimentIcon = iconConfig.icon;

  return (
    <div className="space-y-4">
      {hasExperiment && (
        <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
          <div className="flex items-center gap-2">
            <ExperimentIcon
              className="size-4"
              style={{ color: iconConfig.color }}
            />
            <span className="text-sm font-medium">{iconConfig.label}</span>
            <code className="rounded bg-background px-2 py-0.5 font-mono text-sm">
              {configData.experiment_id}
            </code>
            {hasVariant && (
              <Tag variant="gray" size="sm">
                Variant {configData.assigned_variant}
              </Tag>
            )}
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
              {configRows.map((row) =>
                row.type === "prompt" ? (
                  <ExpandablePromptRow
                    key={row.key}
                    row={row}
                    baseline={baselineData?.values[row.key]}
                    hasExperiment={hasExperiment}
                  />
                ) : (
                  <tr key={row.key} className="border-b last:border-b-0">
                    <td className="px-3 py-2">
                      <div className="flex items-center gap-2">
                        <code className="font-mono text-sm">{row.key}</code>
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      {row.type === "boolean" ? (
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
                )
              )}
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
