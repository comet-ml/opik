import { EvaluationScoreResult } from "@/evaluation/types";
import { MetricComputationError } from "../../errors";
import { extractJsonContentOrRaise } from "../parsingHelpers";
import { logger } from "@/utils/logger";

const GEVAL_SCORE_CALC_FAILED =
  "Failed to calculate g-eval score. The model output could not be parsed.";

export function parseModelOutputString(
  content: string,
  name: string
): EvaluationScoreResult {
  try {
    const dictContent = extractJsonContentOrRaise(content) as Record<
      string,
      unknown
    >;

    const rawScore = dictContent["score"];
    if (rawScore === null || rawScore === undefined) {
      throw new Error(`GEval score is required but got ${rawScore}`);
    }

    const scoreRaw = Number(rawScore);

    if (isNaN(scoreRaw) || scoreRaw < 0 || scoreRaw > 10) {
      throw new Error(
        `LLM returned score outside of [0, 10] range: ${scoreRaw}`
      );
    }

    const normalisedScore = scoreRaw / 10;
    const reason = String(dictContent["reason"] ?? "");

    return {
      name,
      value: normalisedScore,
      reason,
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logger.error(`Failed to parse model output: ${errorMessage}`);

    throw new MetricComputationError(
      GEVAL_SCORE_CALC_FAILED,
      error instanceof Error ? error : undefined
    );
  }
}

interface LogprobEntry {
  token?: string;
  logprob?: number;
  top_logprobs?: Array<{ token?: string; logprob?: number }>;
}

function extractLogprobs(response: unknown): LogprobEntry[][] | undefined {
  if (!response || typeof response !== "object") return undefined;

  const resp = response as Record<string, unknown>;
  const providerMetadata = resp.providerMetadata as
    | Record<string, unknown>
    | undefined;
  if (!providerMetadata) return undefined;

  for (const providerKey of Object.keys(providerMetadata)) {
    const providerData = providerMetadata[providerKey] as
      | Record<string, unknown>
      | undefined;
    if (providerData && providerData.logprobs) {
      return providerData.logprobs as LogprobEntry[][];
    }
  }

  return undefined;
}

function extractTextFromResponse(response: unknown): string {
  if (!response || typeof response !== "object") {
    throw new Error("LLM response is not an object");
  }

  const resp = response as Record<string, unknown>;

  if (typeof resp.text === "string") {
    return resp.text;
  }

  throw new Error("LLM response is missing textual content");
}

const SCORE_TOKEN_POSITION = 3;

export function parseProviderResponse(
  response: unknown,
  name: string
): EvaluationScoreResult {
  try {
    const logprobs = extractLogprobs(response);
    const entries = logprobs?.[0];

    if (!entries || entries.length <= SCORE_TOKEN_POSITION) {
      logger.debug("No logprobs found, falling back to text-based parsing");
      const text = extractTextFromResponse(response);
      return parseModelOutputString(text, name);
    }

    const entry = entries[SCORE_TOKEN_POSITION];
    const topLogprobs = entry.top_logprobs ?? [];
    const tokenCandidate = String(entry.token ?? "");

    let linearProbsSum = 0.0;
    let weightedScoreSum = 0.0;

    for (const candidate of topLogprobs) {
      const tokenStr = String(candidate.token ?? "");
      if (!/^\d+$/.test(tokenStr)) continue;

      const score = parseInt(tokenStr, 10);
      if (score < 0 || score > 10) continue;

      if (candidate.logprob == null) continue;

      const linearProb = Math.exp(candidate.logprob);
      linearProbsSum += linearProb;
      weightedScoreSum += linearProb * score;
    }

    let finalScore: number;

    if (linearProbsSum !== 0.0) {
      finalScore = weightedScoreSum / linearProbsSum / 10;
    } else {
      if (!/^\d+$/.test(tokenCandidate)) {
        throw new MetricComputationError(GEVAL_SCORE_CALC_FAILED);
      }
      finalScore = parseInt(tokenCandidate, 10) / 10;
    }

    if (finalScore < 0.0 || finalScore > 1.0) {
      throw new Error(
        `Failed to compute final score from log_probs, the value is out of [0, 1] range: ${finalScore}`
      );
    }

    const text = extractTextFromResponse(response);
    const reasonData = extractJsonContentOrRaise(text) as Record<
      string,
      unknown
    >;
    const reason = String(reasonData["reason"] ?? "");

    return {
      name,
      value: finalScore,
      reason,
    };
  } catch (error) {
    if (error instanceof MetricComputationError) throw error;

    const errorMessage = error instanceof Error ? error.message : String(error);
    logger.error(`Failed to parse model output: ${errorMessage}`);

    throw new MetricComputationError(
      GEVAL_SCORE_CALC_FAILED,
      error instanceof Error ? error : undefined
    );
  }
}
