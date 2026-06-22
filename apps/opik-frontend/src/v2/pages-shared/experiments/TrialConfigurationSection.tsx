import React, { useMemo, useState } from "react";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import isString from "lodash/isString";
import get from "lodash/get";

import { ChevronRight, GitCompareArrows, List } from "lucide-react";

import { cn } from "@/lib/utils";

import { Experiment } from "@/types/datasets";
import { OptimizationStudioConfig } from "@/types/optimizations";
import { Tag } from "@/ui/tag";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import {
  detectConfigValueType,
  flattenConfig,
  isOptimizerMetaEntry,
  makeSkipKey,
} from "@/lib/configuration-renderer";
import ConfigurationDiffContent from "@/v2/pages-shared/experiments/ConfigurationDiffContent/ConfigurationDiffContent";
import { ConfigurationType } from "@/types/shared";
import {
  isRecord,
  formatPrimitive,
  extractMessages,
  buildConfigFromStudioConfig,
  getMetricParamLabels,
  MessageEntry,
} from "@/lib/optimization-config";
import { extractNamedPrompts } from "@/lib/prompt";

const PROMPT_MAX_LENGTH = 200;
const METRIC_KEY = "Metric";

const CONFIG_VIEW_MODE = {
  CONFIG: "config",
  DIFF_BASELINE: "diff-baseline",
  DIFF_PARENT: "diff-parent",
} as const;

type ConfigViewMode = (typeof CONFIG_VIEW_MODE)[keyof typeof CONFIG_VIEW_MODE];

type TrialConfigurationSectionProps = {
  experiments: Experiment[];
  title?: string;
  referenceExperiment?: Experiment | null;
  parentExperiment?: Experiment | null;
  studioConfig?: OptimizationStudioConfig;
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
        <div className="mb-2 flex items-center gap-2">
          <Tag variant="gray" size="sm" className="capitalize">
            {message.role}
          </Tag>
        </div>
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
  const namedPrompts = useMemo(() => extractNamedPrompts(value), [value]);

  const messages = useMemo(() => extractMessages(value), [value]);
  const text = useMemo(
    () => (isString(value) ? value : JSON.stringify(value, null, 2)),
    [value],
  );

  if (namedPrompts) {
    return (
      <div className="flex flex-col gap-4">
        {Object.entries(namedPrompts).map(([name, msgs]) => {
          const normalized = extractMessages(msgs) ?? [];
          return (
            <div key={name}>
              <p className="comet-body-s mb-1 text-muted-slate">{name}</p>
              <div className="flex flex-col gap-2">
                {normalized.map((msg, i) => (
                  <MessageBlock
                    key={`${name}-${msg.role}-${i}`}
                    message={msg}
                  />
                ))}
              </div>
            </div>
          );
        })}
      </div>
    );
  }

  if (messages) {
    return (
      <div className="flex flex-col gap-2">
        {messages.map((msg, i) => (
          <MessageBlock key={`msg-${msg.role}-${i}`} message={msg} />
        ))}
      </div>
    );
  }

  return (
    <p className="comet-body-s whitespace-pre-wrap break-words rounded-md bg-muted/40 p-3">
      {text}
    </p>
  );
};

const ToolsBlock: React.FC<{ value: unknown }> = ({ value }) => {
  if (!isArray(value)) return null;

  const names = value.map((t) => {
    if (isRecord(t) && "name" in t) {
      return String(t.name);
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

type MetricParam = { key: string; value: unknown };

const MetricEntry: React.FC<{
  label: string;
  value: unknown;
  params: MetricParam[];
}> = ({ label, value, params }) => {
  const [isOpen, setIsOpen] = useState(false);
  return (
    <div>
      <div className="flex items-baseline gap-1.5">
        <span className="comet-body-s text-muted-slate">{label}:</span>
        <button
          type="button"
          onClick={() => setIsOpen((prev) => !prev)}
          className="flex items-center gap-1"
        >
          <span className="comet-body-s-accented">
            {formatPrimitive(value)}
          </span>
          <ChevronRight
            className={cn(
              "size-3.5 shrink-0 text-muted-slate transition-transform duration-200",
              isOpen && "rotate-90",
            )}
          />
        </button>
      </div>
      {isOpen && (
        <div className="mt-1 flex flex-col gap-2 pl-4">
          {params.map(({ key, value: v }) => (
            <div key={key}>
              <p className="comet-body-xs text-muted-slate">{key}</p>
              <p className="comet-body-s whitespace-pre-wrap break-words rounded-md bg-muted/40 pb-3 pr-3">
                {String(v)}
              </p>
            </div>
          ))}
        </div>
      )}
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

const TrialConfigurationSection: React.FC<TrialConfigurationSectionProps> = ({
  experiments,
  title = "Configuration",
  referenceExperiment,
  parentExperiment,
  studioConfig,
}) => {
  const [viewMode, setViewMode] = useState<ConfigViewMode>(
    CONFIG_VIEW_MODE.CONFIG,
  );

  const experiment = experiments[0];
  const configuration = useMemo(() => {
    if (experiment?.metadata && isObject(experiment.metadata)) {
      const config = get(experiment.metadata, "configuration");
      if (config && isObject(config)) {
        const configObj = config as ConfigurationType;

        if ("prompt_messages" in configObj || "model" in configObj) {
          return configObj;
        }

        if ("prompt" in configObj) {
          if (studioConfig) {
            const base = {
              ...(buildConfigFromStudioConfig(studioConfig) as Record<
                string,
                unknown
              >),
            };
            delete base["Initial prompt"];
            return { ...base, ...configObj } as ConfigurationType;
          }
          return configObj;
        }
      }
    }
    if (studioConfig) {
      return buildConfigFromStudioConfig(studioConfig);
    }
    return null;
  }, [experiment, studioConfig]);

  const entries = useMemo(() => {
    if (!configuration) return [];

    const hasStructuredPrompt = "prompt" in configuration;
    const configPrompt = get(configuration, "prompt");
    const promptMessages = configPrompt ? extractMessages(configPrompt) : null;

    const result = flattenConfig(
      configuration,
      makeSkipKey(hasStructuredPrompt),
    );

    if (promptMessages) {
      const filtered = result.filter(
        (e) => e.type !== "prompt" && !isOptimizerMetaEntry(e.key, e.value),
      );
      filtered.push({
        key: "Prompt",
        value: configPrompt,
        type: "prompt",
      });
      return filtered;
    }

    // Fallback: if no prompt entry found, try the top-level "prompt" key
    const hasPrompt = result.some((e) => e.type === "prompt");
    if (!hasPrompt && configPrompt) {
      result.push({
        key: "Prompt",
        value: configPrompt,
        type: "prompt",
      });
    }

    return result;
  }, [configuration]);

  const metricParamKeys = useMemo(
    () => (studioConfig ? getMetricParamLabels(studioConfig) : []),
    [studioConfig],
  );

  if (!configuration) return null;

  const hasDiffBaseline = !!referenceExperiment;
  const hasDiffParent = !!parentExperiment;
  const hasDiffSupport = hasDiffBaseline || hasDiffParent;

  const diffExperiment =
    viewMode === CONFIG_VIEW_MODE.DIFF_PARENT
      ? parentExperiment
      : referenceExperiment;

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
            <ToggleGroupItem value={CONFIG_VIEW_MODE.CONFIG}>
              <List className="mr-1 size-3.5" />
              Configuration
            </ToggleGroupItem>
            {hasDiffBaseline && (
              <ToggleGroupItem value={CONFIG_VIEW_MODE.DIFF_BASELINE}>
                <GitCompareArrows className="mr-1 size-3.5" />
                Diff vs baseline
              </ToggleGroupItem>
            )}
            {hasDiffParent && (
              <ToggleGroupItem value={CONFIG_VIEW_MODE.DIFF_PARENT}>
                <GitCompareArrows className="mr-1 size-3.5" />
                Diff vs parent
              </ToggleGroupItem>
            )}
          </ToggleGroup>
        )}
      </div>

      {(viewMode === CONFIG_VIEW_MODE.DIFF_BASELINE ||
        viewMode === CONFIG_VIEW_MODE.DIFF_PARENT) &&
      diffExperiment ? (
        <div className="flex flex-col gap-4">
          {entries
            .filter(
              ({ type, key }) =>
                type !== "prompt" && !metricParamKeys.includes(key),
            )
            .map(({ key, value, type }) => {
              if (key === METRIC_KEY && metricParamKeys.length > 0) {
                const params = entries.filter((e) =>
                  metricParamKeys.includes(e.key),
                );
                return (
                  <MetricEntry
                    key={key}
                    label={key}
                    value={value}
                    params={params}
                  />
                );
              }
              if (
                type === "number" ||
                type === "string" ||
                type === "boolean"
              ) {
                return (
                  <div key={key} className="flex items-baseline gap-1.5">
                    <span className="comet-body-s text-muted-slate">
                      {key}:
                    </span>
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
          <ConfigurationDiffContent
            baselineExperiment={diffExperiment}
            currentExperiment={experiment}
          />
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {entries
            .filter(({ key }) => !metricParamKeys.includes(key))
            .map(({ key, value, type }) => {
              if (key === METRIC_KEY && metricParamKeys.length > 0) {
                const params = entries.filter((e) =>
                  metricParamKeys.includes(e.key),
                );
                return (
                  <MetricEntry
                    key={key}
                    label={key}
                    value={value}
                    params={params}
                  />
                );
              }
              if (
                type === "number" ||
                type === "string" ||
                type === "boolean"
              ) {
                return (
                  <div key={key} className="flex items-baseline gap-1.5">
                    <span className="comet-body-s text-muted-slate">
                      {key}:
                    </span>
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
