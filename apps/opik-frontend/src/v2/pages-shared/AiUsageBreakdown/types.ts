export type LaneSide = "input" | "output";

export interface LaneView {
  key: string;
  label: string;
  tokens: number;
  // USD, priced FE-side from the lane's cache-tier columns (claudePricing).
  // null when the model is unknown or tier data is absent.
  cost: number | null;
  hasBreakdown: boolean;
  weight: number;
}
