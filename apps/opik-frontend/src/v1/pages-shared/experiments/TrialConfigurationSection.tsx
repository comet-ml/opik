import React, { useMemo, useState } from "react";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import isString from "lodash/isString";
import get from "lodash/get";

import { GitCompareArrows, List } from "lucide-react";

import { Experiment } from "@/types/datasets";
import { OptimizationStudioConfig } from "@/types/optimizations";
import { Tag } from "@/ui/tag";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import {
  detectConfigValueType,
  flattenConfig,
  makeSkipKey,
} from "@/lib/configuration-renderer";
import ConfigurationDiffContent, {
  ConfigurationType,
} from "@/v1/pages-shared/experiments/ConfigurationDiffContent/ConfigurationDiffContent";
import {
  isRecord,
  formatPrimitive,
  extractMessages,
  buildConfigFromStudioConfig,
  MessageEntry,
} from "@/lib/optimization-config";

const PROMPT_MAX_LENGTH = 200;

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
  const text = useMemo(
    () => (isString(value) ? value : JSON.stringify(value, null, 2)),
    [value],
  );

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

  const entries = useMemo(() => {
    if (!configuration) return [];

    const hasStructuredPrompt = "prompt" in configuration;
    const result = flattenConfig(
      configuration,
      makeSkipKey(hasStructuredPrompt),
    );

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
        <ConfigurationDiffContent
          baselineExperiment={diffExperiment}
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
