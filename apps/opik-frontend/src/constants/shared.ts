import React from "react";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  ROW_HEIGHT,
} from "@/types/shared";

export const DEMO_PROJECT_NAME = "Demo Project";
export const USER_FEEDBACK_NAME = "User feedback";

export const TABLE_HEADER_Z_INDEX = 2;
export const TABLE_ROW_Z_INDEX = 0;

export const ROW_HEIGHT_MAP: Record<ROW_HEIGHT, React.CSSProperties> = {
  [ROW_HEIGHT.small]: { height: "44px" },
  [ROW_HEIGHT.medium]: { height: "96px" },
  [ROW_HEIGHT.large]: { height: "296px" },
};

export const CELL_VERTICAL_ALIGNMENT_MAP = {
  [CELL_VERTICAL_ALIGNMENT.start]: "items-start",
  [CELL_VERTICAL_ALIGNMENT.center]: "items-center",
  [CELL_VERTICAL_ALIGNMENT.end]: "items-end",
};

export const CELL_HORIZONTAL_ALIGNMENT_MAP: Record<COLUMN_TYPE, string> = {
  [COLUMN_TYPE.number]: "justify-end",
  [COLUMN_TYPE.cost]: "justify-end",
  [COLUMN_TYPE.duration]: "justify-end",
  [COLUMN_TYPE.string]: "justify-start",
  [COLUMN_TYPE.list]: "justify-start",
  [COLUMN_TYPE.time]: "justify-start",
  [COLUMN_TYPE.dictionary]: "justify-start",
  [COLUMN_TYPE.numberDictionary]: "justify-start",
  [COLUMN_TYPE.guardrails]: "justify-start",
};
