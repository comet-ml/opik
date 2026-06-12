import useAiSpendComposition from "./useAiSpendComposition";
import { tierCost } from "./claudePricing";

// Prices token-denominated savings at the window's blended per-token rate
// (window cost / window tokens, both FE-priced from cc.billing tiers).
// Composition is cached by react-query, so callers on pages that already
// render the breakdown pay no extra request.
export default function useSavingsPricer(params: {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
}): (tokens: number | null | undefined) => number | null {
  const { data } = useAiSpendComposition(params);
  const model = data?.models?.[0] ?? null;
  const lanes = [...(data?.input?.lanes ?? []), ...(data?.output?.lanes ?? [])];
  const totalCost = lanes.reduce<number | null>((acc, lane) => {
    const cost = tierCost(lane, model);
    if (cost == null) return acc;
    return (acc ?? 0) + cost;
  }, null);
  const totalTokens =
    (data?.input?.total_tokens ?? 0) + (data?.output?.total_tokens ?? 0);

  return (tokens) => {
    if (tokens == null || totalCost == null || totalTokens <= 0) return null;
    return (tokens / totalTokens) * totalCost;
  };
}
