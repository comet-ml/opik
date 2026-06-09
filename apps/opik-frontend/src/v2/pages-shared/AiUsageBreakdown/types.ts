export type LaneSide = "input" | "output";

export interface LaneView {
  key: string;
  label: string;
  tokens: number;
  cost: number | null;
  hasBreakdown: boolean;
  // cost when available, else tokens — drives ribbon thickness and % share.
  weight: number;
}
