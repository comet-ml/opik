import { DatasetItem, DATASET_ITEM_SOURCE, Evaluator } from "@/types/datasets";
import { buildLLMJudgeBEConfig } from "@/lib/evaluator-converters";
import {
  OPIK_DESCRIPTION_FIELD,
  OPIK_EVALUATOR_ASSERTIONS_FIELD,
} from "@/constants/datasets";

/** Filter valid assertion strings from raw AI-generated data */
export function parseAssertions(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (a): a is string => typeof a === "string" && a.trim().length > 0,
  );
}

/** Strip _opik_* metadata from raw data, returning clean data + parsed metadata */
export function extractOpikMetadata(rawData: Record<string, unknown>): {
  data: Record<string, unknown>;
  description: string | undefined;
  assertions: string[];
} {
  const {
    [OPIK_DESCRIPTION_FIELD]: rawDescription,
    [OPIK_EVALUATOR_ASSERTIONS_FIELD]: rawAssertions,
    ...data
  } = rawData;

  return {
    data,
    description:
      typeof rawDescription === "string" ? rawDescription : undefined,
    assertions: parseAssertions(rawAssertions),
  };
}

/** Build a new draft item from an AI-generated sample */
export function buildDraftItemFromSample(
  item: DatasetItem,
  now: string,
): Omit<DatasetItem, "id"> {
  const { data, description, assertions } = extractOpikMetadata(
    item.data as Record<string, unknown>,
  );

  const evaluators: Evaluator[] | undefined =
    assertions.length > 0
      ? [
          {
            name: "LLM Judge",
            type: "llm_judge",
            config: buildLLMJudgeBEConfig({ assertions }),
          },
        ]
      : undefined;

  return {
    data,
    description,
    evaluators,
    source: DATASET_ITEM_SOURCE.manual,
    tags: item.tags || [],
    created_at: now,
    last_updated_at: now,
  };
}
