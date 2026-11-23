export type ModelPriceKind = "fixed" | "alias";

export type PriceField =
  | "input_price"
  | "output_price"
  | "cache_read_price"
  | "cache_write_price";

export const PRICE_FIELDS: PriceField[] = [
  "input_price",
  "output_price",
  "cache_read_price",
  "cache_write_price",
];

export type PriceSource = "organization" | "built_in" | "none";

export interface ModelPriceAliasOf {
  model: string;
  provider?: string | null;
}

export interface ModelPriceRow {
  id?: string | null;
  model: string;
  provider?: string | null;
  kind: ModelPriceKind;
  input_price?: string | null;
  output_price?: string | null;
  cache_read_price?: string | null;
  cache_write_price?: string | null;
  alias_of?: ModelPriceAliasOf | null;
  note?: string | null;
  created_at?: string | null;
  created_by?: string | null;
  last_updated_at?: string | null;
  last_updated_by?: string | null;
}

export interface ModelPriceList {
  version: number;
  rows: ModelPriceRow[];
}

export interface ModelPriceReplace {
  rows: ModelPriceRow[];
  expected_version: number;
}

export interface ModelPriceProblem {
  row?: number | null;
  field?: string | null;
  code: string;
  message: string;
}

export interface ModelPriceProblems {
  errors: ModelPriceProblem[];
  warnings: ModelPriceProblem[];
}

export interface ModelPriceChange {
  before: ModelPriceRow;
  after: ModelPriceRow;
}

export interface ModelPriceReplaceResult {
  version: number;
  saved: boolean;
  created: ModelPriceRow[];
  updated: ModelPriceChange[];
  deleted: ModelPriceRow[];
  unchanged: number;
  warnings: ModelPriceProblem[];
}

export type ModelPriceHistoryAction = "created" | "updated" | "deleted";

export interface ModelPriceHistoryEntry {
  list_version: number;
  row_id: string;
  action: ModelPriceHistoryAction;
  before?: ModelPriceRow | null;
  after?: ModelPriceRow | null;
  created_at: string;
  created_by: string;
}

export interface ModelPriceHistoryPage {
  page: number;
  size: number;
  total: number;
  content: ModelPriceHistoryEntry[];
}

export interface ModelUsedWorkspace {
  id: string;
  name: string;
}

export interface ModelUsed {
  model: string;
  provider: string;
  last_seen: string;
  days_used: number;
  workspaces: ModelUsedWorkspace[];
  price_source: PriceSource;
}

export interface ModelsUsed {
  days: number;
  truncated: boolean;
  content: ModelUsed[];
}
