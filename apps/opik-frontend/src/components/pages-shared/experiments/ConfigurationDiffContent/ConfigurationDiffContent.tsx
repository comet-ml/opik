import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import get from "lodash/get";
import uniq from "lodash/uniq";

import { Experiment } from "@/types/datasets";
import { detectConfigValueType } from "@/lib/configuration-renderer";
import DiffSection from "./DiffSection";

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
