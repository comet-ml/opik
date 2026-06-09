import {
  AiSpendLaneApi,
  AiSpendSideApi,
} from "@/api/ai-spend/useAiSpendComposition";
import { LaneView } from "./types";

const MAX_RIBBON_WIDTH = 14;

// Decision: ribbons + share are weighted by cost; until the backend populates
// per-lane cost it falls back to tokens. Switches automatically when cost lands.
export const laneWeight = (lane: {
  total_estimated_cost: number | null;
  total_tokens: number;
}): number => {
  if (
    typeof lane.total_estimated_cost === "number" &&
    lane.total_estimated_cost > 0
  ) {
    return lane.total_estimated_cost;
  }
  return lane.total_tokens ?? 0;
};

export const toLaneView = (lane: AiSpendLaneApi): LaneView => ({
  key: lane.key,
  label: lane.label,
  tokens: lane.total_tokens ?? 0,
  cost: lane.total_estimated_cost,
  hasBreakdown: lane.has_breakdown,
  weight: laneWeight(lane),
});

export const toLaneViews = (side?: AiSpendSideApi): LaneView[] =>
  (side?.lanes ?? []).map(toLaneView);

export const sideWeightTotal = (lanes: LaneView[]): number =>
  lanes.reduce((acc, lane) => acc + lane.weight, 0);

export const lanePct = (weight: number, total: number): number =>
  total > 0 ? (weight / total) * 100 : 0;

export const ribbonWidth = (weight: number, maxWeight: number): number =>
  Math.max(1.5, Math.sqrt(weight / Math.max(maxWeight, 1)) * MAX_RIBBON_WIDTH);
