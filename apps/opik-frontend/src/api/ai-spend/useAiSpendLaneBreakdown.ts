import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { useAiSpend } from "@/contexts/AiSpendContext";

export interface AiSpendBreakdownItemApi {
  label: string;
  total_tokens: number;
  count?: number;
  // When present, the bar renders as stacked segments: always-on definition
  // cost (muted) + on-demand usage cost (solid). Sum = total_tokens.
  definition_tokens?: number;
  usage_tokens?: number;
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
  // Tier sums for the lane's window total; priced FE-side (claudePricing).
  input_tokens?: number;
  cache_read_tokens?: number;
  cache_creation_tokens?: number;
  output_tokens?: number;
  // Representative cc.billing model, for FE pricing.
  model?: string;
  item_count: number;
  // Noun for item counts (e.g. "prompts") - UI falls back to "items".
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
  workspaceName?: string;
};

const getAiSpendLaneBreakdown = async (
  { signal }: QueryFunctionContext,
  {
    laneKey,
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
    workspaceName,
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
    {
      signal,
      ...(workspaceName && {
        headers: { "Comet-Workspace": workspaceName },
      }),
    },
  );

  return data;
};

export default function useAiSpendLaneBreakdown(
  params: Omit<UseAiSpendLaneBreakdownParams, "workspaceName">,
  options?: QueryConfig<AiSpendBreakdownResponse>,
) {
  const { spendWorkspaceName } = useAiSpend();
  const queryParams = { ...params, workspaceName: spendWorkspaceName };

  return useQuery({
    queryKey: ["ai-spend-lane-breakdown", queryParams],
    queryFn: (context) => getAiSpendLaneBreakdown(context, queryParams),
    enabled: Boolean(params.laneKey),
    ...options,
  });
}
