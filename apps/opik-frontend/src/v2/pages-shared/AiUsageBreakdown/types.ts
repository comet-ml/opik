export type LaneSide = "input" | "output";

export interface LaneView {
  key: string;
  label: string;
  tokens: number;
  hasBreakdown: boolean;
  weight: number;
}
