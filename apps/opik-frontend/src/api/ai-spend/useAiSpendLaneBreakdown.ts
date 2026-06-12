import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ModelTiers } from "./claudePricing";

export interface AiSpendBreakdownItemApi {
  label: string;
  total_tokens: number;
  count?: number;
  // When present, the bar renders as stacked segments: always-on definition
  // cost (muted) + on-demand usage cost (solid). Sum = total_tokens.
  definition_tokens?: number;
  usage_tokens?: number;
  // Per-model tier sums; priced FE-side (claudePricing).
  by_model: ModelTiers[];
}

export interface AiSpendBreakdownSectionApi {
  title: string;
  items: AiSpendBreakdownItemApi[];
  // Noun for this section's item counts (e.g. "calls").
  item_unit?: string;
}

export interface AiSpendBreakdownResponse {
  lane_key: string;
  title: string;
  subtitle?: string;
  total_tokens: number;
  // Per-model tier sums for the lane's window total; priced FE-side.
  by_model: ModelTiers[];
  item_count: number;
  // Singular noun for item_count / item.count ("prompt", "call", "load").
  // Absent when counts are structurally 0 for the lane - the UI then hides
  // the count column and header segment.
  item_unit?: string;
  items: AiSpendBreakdownItemApi[];
  // Title of the main items card - defaults to "Cost breakdown" in the UI
  // (e.g. "Top MCP servers").
  items_title?: string;
  // Noun for the main items' per-row counts (e.g. "calls") when it differs
  // from item_unit (the header noun, e.g. "servers").
  items_unit?: string;
  // Additional independent slices of the lane, rendered as separate cards
  // below the main breakdown.
  sections?: AiSpendBreakdownSectionApi[];
}

type UseAiSpendLaneBreakdownParams = {
  laneKey: string;
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
};

const getAiSpendLaneBreakdown = async (
  { signal }: QueryFunctionContext,
  {
    laneKey,
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  }: UseAiSpendLaneBreakdownParams,
) => {
  const { data } = await api.post<AiSpendBreakdownResponse>(
    `${AI_SPEND_REST_ENDPOINT}composition/${laneKey}/breakdown`,
    {
      project_name: projectName,
      interval_start: intervalStart,
      interval_end: intervalEnd,
      ...(userUuid && { user_id: userUuid }),
    },
    { signal },
  );

  return data;
};

export default function useAiSpendLaneBreakdown(
  params: UseAiSpendLaneBreakdownParams,
  options?: QueryConfig<AiSpendBreakdownResponse>,
) {
  return useQuery({
    queryKey: ["ai-spend-lane-breakdown", params],
    queryFn: (context) => getAiSpendLaneBreakdown(context, params),
    enabled: Boolean(params.laneKey),
    ...options,
  });
}
