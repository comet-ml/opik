import {
  AiSpendLaneApi,
  AiSpendSideApi,
} from "@/api/ai-spend/useAiSpendComposition";
import { laneCost } from "@/api/ai-spend/claudePricing";
import { LaneView } from "./types";

const MAX_RIBBON_WIDTH = 14;

export const laneWeight = (lane: { total_tokens: number }): number =>
  lane.total_tokens ?? 0;

export const toLaneView = (
  lane: AiSpendLaneApi,
  model?: string | null,
): LaneView => ({
  key: lane.key,
  label: lane.label,
  tokens: lane.total_tokens ?? 0,
  cost: laneCost(lane, model),
  hasBreakdown: lane.has_breakdown,
  weight: laneWeight(lane),
});

export const toLaneViews = (
  side?: AiSpendSideApi,
  model?: string | null,
): LaneView[] => (side?.lanes ?? []).map((lane) => toLaneView(lane, model));

export const sideWeightTotal = (lanes: LaneView[]): number =>
  lanes.reduce((acc, lane) => acc + lane.weight, 0);

// Sum of lane costs; null when no lane could be priced (unknown model or
// pre-billing traces) so the UI hides the figure instead of showing $0.
export const sideCostTotal = (lanes: LaneView[]): number | null => {
  const priced = lanes.filter((lane) => lane.cost != null);
  if (priced.length === 0) {
    return null;
  }
  return priced.reduce((acc, lane) => acc + (lane.cost as number), 0);
};

export const lanePct = (weight: number, total: number): number =>
  total > 0 ? (weight / total) * 100 : 0;

export const ribbonWidth = (weight: number, maxWeight: number): number =>
  Math.max(1.5, Math.sqrt(weight / Math.max(maxWeight, 1)) * MAX_RIBBON_WIDTH);
