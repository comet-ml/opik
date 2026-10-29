export type NumberParam = {
  accepted: boolean;
  min?: number;
  max?: number;
  default?: number;
};

export type FlagParam = {
  accepted: boolean;
};

export type EffortParam = {
  accepted: boolean;
  levels?: string[];
  default?: string;
};

export type ModelParams = {
  temperature?: NumberParam;
  top_p?: NumberParam;
  top_k?: NumberParam;
  frequency_penalty?: NumberParam;
  presence_penalty?: NumberParam;
  max_output_tokens?: NumberParam;
  stop?: FlagParam;
  seed?: FlagParam;
  effort?: EffortParam;
  sampling_exclusive?: boolean;
  thinks_by_default?: boolean;
  routes?: Record<string, ModelParams>;
};

export type ModelParamsByProvider = Record<string, ModelParams>;
