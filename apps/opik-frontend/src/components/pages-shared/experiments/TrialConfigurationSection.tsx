import React, { useMemo, useState } from "react";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import isString from "lodash/isString";
import get from "lodash/get";

import { GitCompareArrows, List } from "lucide-react";

import { Experiment } from "@/types/datasets";
import { OptimizationStudioConfig } from "@/types/optimizations";
import { Tag } from "@/components/ui/tag";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { detectConfigValueType } from "@/lib/configuration-renderer";
import { getOptimizerLabel } from "@/lib/optimizations";
import { OPTIMIZATION_METRIC_OPTIONS } from "@/constants/optimizations";
import ConfigurationDiffContent from "@/components/pages-shared/experiments/ConfigurationDiffContent/ConfigurationDiffContent";

const PROMPT_MAX_LENGTH = 200;

const EXCLUDED_CONFIG_KEYS = ["prompt", "examples"];

const REDUNDANT_WHEN_STRUCTURED = [
  "system_prompt",
  "user_prompt",
  "user_message",
];

type ConfigViewMode = "config" | "diff";

type TrialConfigurationSectionProps = {
  experiments: Experiment[];
  title?: string;
  referenceExperiment?: Experiment | null;
  studioConfig?: OptimizationStudioConfig;
};

const formatPrimitive = (value: unknown): string => {
  if (value == null) return "-";
  if (typeof value === "boolean") return value ? "true" : "false";
  return String(value);
};

type MessageEntry = { role: string; content: string };

const extractMessages = (value: unknown): MessageEntry[] | null => {
  const toEntries = (arr: unknown[]): MessageEntry[] =>
    arr.map((m) => {
      if (isObject(m) && "content" in (m as Record<string, unknown>)) {
        return {
          role: String(get(m, "role", "")),
          content: String(get(m, "content", "")),
        };
      }
      return { role: "", content: JSON.stringify(m) };
    });

  if (isArray(value)) return toEntries(value);
  if (isObject(value) && "messages" in (value as Record<string, unknown>)) {
    const messages = get(value, "messages", []);
    if (isArray(messages)) return toEntries(messages);
  }
  return null;
};

const MessageBlock: React.FC<{ message: MessageEntry }> = ({ message }) => {
  const [expanded, setExpanded] = useState(false);
  const needsTruncation = message.content.length > PROMPT_MAX_LENGTH;
  const displayText =
    !expanded && needsTruncation
      ? message.content.slice(0, PROMPT_MAX_LENGTH) + "..."
      : message.content;

  return (
    <div className="rounded-md border bg-muted/30 p-3">
      {message.role && (
        <Tag variant="gray" size="sm" className="mb-2 capitalize">
          {message.role}
        </Tag>
      )}
      <p className="comet-body-s whitespace-pre-wrap break-words">
        {displayText}
      </p>
      {needsTruncation && (
        <button
          onClick={() => setExpanded((prev) => !prev)}
          className="comet-body-s mt-1 text-[hsl(var(--primary))] hover:underline"
        >
          {expanded ? "Show less" : "See more..."}
        </button>
      )}
    </div>
  );
};

const PromptBlock: React.FC<{ value: unknown }> = ({ value }) => {
  const messages = useMemo(() => extractMessages(value), [value]);

  if (messages) {
    return (
      <div className="flex flex-col gap-2">
        {messages.map((msg, i) => (
          <MessageBlock key={i} message={msg} />
        ))}
      </div>
    );
  }

  const text = isString(value) ? value : JSON.stringify(value, null, 2);

  return (
    <p className="comet-body-s whitespace-pre-wrap break-words rounded-md bg-muted/40 p-3">
      {text}
    </p>
  );
};

const ToolsBlock: React.FC<{ value: unknown }> = ({ value }) => {
  if (!isArray(value)) return null;

  const names = value.map((t) => {
    if (isObject(t) && "name" in (t as Record<string, unknown>)) {
      return String((t as Record<string, unknown>).name);
    }
    if (isString(t)) return t;
    return JSON.stringify(t);
  });

  return (
    <div className="flex flex-wrap gap-1.5">
      {names.map((name) => (
        <Tag key={name} variant="gray" size="sm">
          {name}
        </Tag>
      ))}
    </div>
  );
};

const ConfigEntry: React.FC<{ label: string; value: unknown }> = ({
  label,
  value,
}) => {
  const type = detectConfigValueType(label, value);

  if (type === "prompt") {
    return (
      <div>
        <h4 className="comet-body-s-accented mb-1">{label}</h4>
        <PromptBlock value={value} />
      </div>
    );
  }

  if (type === "tools") {
    return (
      <div>
        <h4 className="comet-body-s-accented mb-1">{label}</h4>
        <ToolsBlock value={value} />
      </div>
    );
  }

  if (type === "json_object") {
    return (
      <div>
        <h4 className="comet-body-s-accented mb-1">{label}</h4>
        <pre className="comet-code overflow-x-auto rounded-md bg-muted/40 p-3 text-sm">
          {JSON.stringify(value, null, 2)}
        </pre>
      </div>
    );
  }

  return null;
};

const getMetricLabel = (type: string): string =>
  OPTIMIZATION_METRIC_OPTIONS.find((opt) => opt.value === type)?.label || type;

const buildConfigFromStudioConfig = (
  studioConfig: OptimizationStudioConfig,
): Record<string, unknown> => {
  const config: Record<string, unknown> = {};

  if (studioConfig.dataset_name) {
    config.Dataset = studioConfig.dataset_name;
  }

  if (studioConfig.llm_model?.model) {
    config.Model = String(studioConfig.llm_model.model);
  }

  if (studioConfig.optimizer?.type) {
    config.Algorithm = getOptimizerLabel(studioConfig.optimizer.type);
  }

  if (studioConfig.evaluation?.metrics?.[0]?.type) {
    config.Metric = getMetricLabel(studioConfig.evaluation.metrics[0].type);
  }

  if (studioConfig.prompt?.messages) {
    config["Initial prompt"] = studioConfig.prompt.messages;
  }

  return config;
};

const TrialConfigurationSection: React.FC<TrialConfigurationSectionProps> = ({
  experiments,
  title = "Configuration",
  referenceExperiment,
  studioConfig,
}) => {
  const [viewMode, setViewMode] = useState<ConfigViewMode>("config");

  const experiment = experiments[0];
  const configuration = useMemo(() => {
    if (experiment?.metadata && isObject(experiment.metadata)) {
      const config = get(experiment.metadata, "configuration");
      if (config && isObject(config)) {
        const configObj = config as Record<string, unknown>;
        const hasRichContent =
          "prompt_messages" in configObj || "model" in configObj;
        if (hasRichContent || !studioConfig) {
          return configObj;
        }
      }
    }
    if (studioConfig) {
      return buildConfigFromStudioConfig(studioConfig);
    }
    return null;
  }, [experiment, studioConfig]);

  type ConfigEntryItem = {
    key: string;
    value: unknown;
    type: ReturnType<typeof detectConfigValueType>;
  };

  const entries = useMemo(() => {
    if (!configuration) return [];

    const result: ConfigEntryItem[] = [];

    // When a structured "prompt" key exists, skip individual keys
    // like system_prompt / user_message that duplicate its content.
    const hasStructuredPrompt = "prompt" in configuration;
    const shouldSkipKey = (key: string) =>
      hasStructuredPrompt && REDUNDANT_WHEN_STRUCTURED.includes(key);

    const collectEntries = (obj: Record<string, unknown>, prefix: string) => {
      for (const [key, value] of Object.entries(obj)) {
        if (!prefix && EXCLUDED_CONFIG_KEYS.includes(key)) continue;
        if (!prefix && shouldSkipKey(key)) continue;

        const path = prefix ? `${prefix}.${key}` : key;
        const type = detectConfigValueType(key, value);

        if (type === "json_object" && isObject(value) && !isArray(value)) {
          collectEntries(value as Record<string, unknown>, path);
        } else {
          result.push({ key: path, value, type });
        }
      }
    };

    collectEntries(configuration, "");

    // If no prompt was found (old format without structured "prompt" key),
    // try the top-level "prompt" as a fallback.
    const hasPrompt = result.some((e) => e.type === "prompt");
    if (!hasPrompt) {
      const configPrompt = get(configuration, "prompt");
      if (configPrompt) {
        result.push({
          key: "Prompt",
          value: configPrompt,
          type: "prompt",
        });
      }
    }

    return result;
  }, [configuration]);

  if (!configuration) return null;

  const hasDiffSupport = !!referenceExperiment;

  return (
    <div className="rounded-lg border bg-muted/20 p-6">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="comet-title-s">{title}</h3>
        {hasDiffSupport && (
          <ToggleGroup
            type="single"
            value={viewMode}
            onValueChange={(v) => {
              if (v) setViewMode(v as ConfigViewMode);
            }}
            variant="default"
            size="sm"
          >
            <ToggleGroupItem value="config">
              <List className="mr-1 size-3.5" />
              Config
            </ToggleGroupItem>
            <ToggleGroupItem value="diff">
              <GitCompareArrows className="mr-1 size-3.5" />
              Diff
            </ToggleGroupItem>
          </ToggleGroup>
        )}
      </div>

      {viewMode === "diff" && hasDiffSupport ? (
        <ConfigurationDiffContent
          baselineExperiment={referenceExperiment}
          currentExperiment={experiment}
        />
      ) : (
        <div className="flex flex-col gap-4">
          {entries.map(({ key, value, type }) => {
            if (type === "number" || type === "string" || type === "boolean") {
              return (
                <div key={key} className="flex items-baseline gap-1.5">
                  <span className="comet-body-s text-muted-slate">{key}:</span>
                  <span className="comet-body-s-accented">
                    {formatPrimitive(value)}
                  </span>
                </div>
              );
            }

            return (
              <div key={key}>
                <ConfigEntry label={key} value={value} />
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default TrialConfigurationSection;
