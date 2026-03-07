import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import get from "lodash/get";
import uniq from "lodash/uniq";

import { Experiment } from "@/types/datasets";
import { detectConfigValueType } from "@/lib/configuration-renderer";
import PromptDiff from "@/components/shared/CodeDiff/PromptDiff";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import { Tag } from "@/components/ui/tag";

type ConfigurationDiffContentProps = {
  baselineExperiment?: Experiment | null;
  currentExperiment?: Experiment | null;
};

const formatValue = (value: unknown): string => {
  if (value == null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean")
    return String(value);
  return JSON.stringify(value, null, 2);
};

type DiffSectionProps = {
  label: string;
  baselineValue: unknown;
  currentValue: unknown;
};

const DiffSection: React.FC<DiffSectionProps> = ({
  label,
  baselineValue,
  currentValue,
}) => {
  const type = detectConfigValueType(label, currentValue ?? baselineValue);
  const baseStr = formatValue(baselineValue);
  const currStr = formatValue(currentValue);
  const hasChanged = baseStr !== currStr;
  const isAdded = baselineValue == null && currentValue != null;
  const isRemoved = baselineValue != null && currentValue == null;

  if (!hasChanged) return null;

  return (
    <div className="rounded-md border p-3">
      <div className="mb-2 flex items-center gap-2">
        <span className="comet-body-s-accented">{label}</span>
        {isAdded && (
          <span className="comet-body-xs rounded-full bg-green-100 px-2 py-0.5 text-green-700">
            Added
          </span>
        )}
        {isRemoved && (
          <span className="comet-body-xs rounded-full bg-red-100 px-2 py-0.5 text-red-700">
            Removed
          </span>
        )}
        {!isAdded && !isRemoved && (
          <span className="comet-body-xs rounded-full bg-amber-100 px-2 py-0.5 text-amber-700">
            Changed
          </span>
        )}
      </div>
      <div className="comet-code whitespace-pre-wrap break-words text-sm">
        {type === "prompt" ? (
          <PromptDiff baseline={baselineValue} current={currentValue} />
        ) : type === "tools" ? (
          <ToolsDiff
            baseline={isArray(baselineValue) ? baselineValue : []}
            current={isArray(currentValue) ? currentValue : []}
          />
        ) : type === "json_object" ? (
          <TextDiff content1={baseStr} content2={currStr} mode="lines" />
        ) : (
          <TextDiff content1={baseStr} content2={currStr} mode="words" />
        )}
      </div>
    </div>
  );
};

const ToolsDiff: React.FC<{
  baseline: unknown[];
  current: unknown[];
}> = ({ baseline, current }) => {
  const baseNames = baseline.map((t) =>
    isObject(t) && "name" in (t as Record<string, unknown>)
      ? String((t as Record<string, unknown>).name)
      : JSON.stringify(t),
  );
  const currNames = current.map((t) =>
    isObject(t) && "name" in (t as Record<string, unknown>)
      ? String((t as Record<string, unknown>).name)
      : JSON.stringify(t),
  );

  const allNames = uniq([...baseNames, ...currNames]);

  return (
    <div className="flex flex-wrap gap-1.5">
      {allNames.map((name) => {
        const inBase = baseNames.includes(name);
        const inCurr = currNames.includes(name);

        if (inBase && inCurr) {
          return (
            <Tag key={name} variant="gray" size="sm">
              {name}
            </Tag>
          );
        }
        if (inCurr && !inBase) {
          return (
            <Tag key={name} variant="green" size="sm">
              + {name}
            </Tag>
          );
        }
        return (
          <Tag key={name} variant="red" size="sm">
            - {name}
          </Tag>
        );
      })}
    </div>
  );
};

const getConfiguration = (
  metadata: object | undefined,
): Record<string, unknown> | null => {
  if (!metadata || !isObject(metadata)) return null;
  const config = get(metadata, "configuration");
  if (!config || !isObject(config)) return null;
  return config as Record<string, unknown>;
};

const ConfigurationDiffContent: React.FunctionComponent<
  ConfigurationDiffContentProps
> = ({ baselineExperiment, currentExperiment }) => {
  const { promptDiffs, configDiffs } = useMemo(() => {
    const baseConfig = getConfiguration(baselineExperiment?.metadata) ?? {};
    const currConfig = getConfiguration(currentExperiment?.metadata) ?? {};

    const EXCLUDED_KEYS = ["prompt", "examples"];

    const prompts: { key: string; baseline: unknown; current: unknown }[] = [];
    const flatBase: Record<string, unknown> = {};
    const flatCurr: Record<string, unknown> = {};

    // When a structured "prompt" key exists (messages array), skip
    // individual keys like system_prompt / user_message that duplicate it.
    const basePrompt = get(baseConfig, "prompt", null);
    const currPrompt = get(currConfig, "prompt", null);
    const hasStructuredPrompt = !!(basePrompt || currPrompt);

    const REDUNDANT_WHEN_STRUCTURED = [
      "system_prompt",
      "user_prompt",
      "user_message",
    ];

    const shouldSkipKey = (key: string) =>
      hasStructuredPrompt && REDUNDANT_WHEN_STRUCTURED.includes(key);

    const collectFlatFiltered = (
      obj: Record<string, unknown>,
      target: Record<string, unknown>,
      prefix: string,
    ) => {
      for (const [key, value] of Object.entries(obj)) {
        if (!prefix && EXCLUDED_KEYS.includes(key)) continue;
        if (!prefix && shouldSkipKey(key)) continue;
        const path = prefix ? `${prefix}.${key}` : key;
        const type = detectConfigValueType(key, value);

        if (type === "prompt" || type === "tools") {
          // handled separately
        } else if (
          type === "json_object" &&
          isObject(value) &&
          !isArray(value)
        ) {
          collectFlatFiltered(value as Record<string, unknown>, target, path);
        } else {
          target[path] = value;
        }
      }
    };

    const collectPromptsFiltered = (
      base: Record<string, unknown>,
      curr: Record<string, unknown>,
      prefix: string,
    ) => {
      const allKeys = uniq([...Object.keys(base), ...Object.keys(curr)]);

      for (const key of allKeys) {
        if (!prefix && EXCLUDED_KEYS.includes(key)) continue;
        if (!prefix && shouldSkipKey(key)) continue;
        const path = prefix ? `${prefix}.${key}` : key;
        const bVal = base[key];
        const cVal = curr[key];
        const type = detectConfigValueType(key, cVal ?? bVal);

        if (type === "prompt" || type === "tools") {
          if (JSON.stringify(bVal) !== JSON.stringify(cVal)) {
            prompts.push({
              key: path,
              baseline: bVal ?? null,
              current: cVal ?? null,
            });
          }
        } else if (
          type === "json_object" &&
          (isObject(bVal) || isObject(cVal)) &&
          !isArray(bVal) &&
          !isArray(cVal)
        ) {
          collectPromptsFiltered(
            (bVal as Record<string, unknown>) ?? {},
            (cVal as Record<string, unknown>) ?? {},
            path,
          );
        }
      }
    };

    collectFlatFiltered(baseConfig, flatBase, "");
    collectFlatFiltered(currConfig, flatCurr, "");
    collectPromptsFiltered(baseConfig, currConfig, "");

    // Handle top-level "prompt" key
    if (
      hasStructuredPrompt &&
      JSON.stringify(basePrompt) !== JSON.stringify(currPrompt)
    ) {
      prompts.unshift({
        key: "Prompt",
        baseline: basePrompt,
        current: currPrompt,
      });
    }

    const allFlatKeys = uniq([
      ...Object.keys(flatBase),
      ...Object.keys(flatCurr),
    ]).sort();

    const diffs = allFlatKeys
      .map((key) => ({
        key,
        baseline: flatBase[key] ?? null,
        current: flatCurr[key] ?? null,
      }))
      .filter((d) => formatValue(d.baseline) !== formatValue(d.current));

    return {
      promptDiffs: prompts,
      configDiffs: diffs,
    };
  }, [baselineExperiment, currentExperiment]);

  const hasDiffs = promptDiffs.length > 0 || configDiffs.length > 0;

  return (
    <div className="space-y-3">
      {!hasDiffs && (
        <div className="py-8 text-center text-muted-foreground">
          No differences found.
        </div>
      )}
      {promptDiffs.map((diff) => (
        <DiffSection
          key={diff.key}
          label={diff.key}
          baselineValue={diff.baseline}
          currentValue={diff.current}
        />
      ))}
      {configDiffs.map((diff) => (
        <DiffSection
          key={diff.key}
          label={diff.key}
          baselineValue={diff.baseline}
          currentValue={diff.current}
        />
      ))}
    </div>
  );
};

export default ConfigurationDiffContent;
