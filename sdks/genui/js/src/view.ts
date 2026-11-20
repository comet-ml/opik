export type Area = "widget";
export const AREAS: readonly Area[] = ["widget"];

export interface QueryInput {
  kind: "query";
  query_id: string;
  columns?: Record<string, string>;
}

export type ViewInput = QueryInput;
export type ViewInputs = Record<string, ViewInput>;
export const INPUT_KINDS: readonly string[] = ["query"];

export type Library = "vega";
export const LIBRARIES: readonly Library[] = ["vega"];

export interface View {
  type: "html";
  engine_version: number;
  html: string;
  inputs: ViewInputs;
  libraries?: readonly Library[];
}

export type ViewData = Record<string, unknown>;

export const MAX_HTML_LENGTH = 1_000_000;
export const RECOMMENDED_HTML_KB = 20;
export const MAX_FRAME_HEIGHT = 1200;
