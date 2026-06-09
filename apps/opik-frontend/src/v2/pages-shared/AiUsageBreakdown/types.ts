export type LaneSide = "input" | "output";

export interface LaneView {
  key: string;
  label: string;
  tokens: number;
  cost: number | null;
  hasBreakdown: boolean;
  weight: number;
}
