export type DateRangePreset =
  | "past24hours"
  | "past3days"
  | "past7days"
  | "past30days"
  | "past60days"
  | "alltime";

export type DateRangeValue = {
  from: Date;
  to: Date;
};

export type DateRangeSerializedValue = DateRangePreset | string;
