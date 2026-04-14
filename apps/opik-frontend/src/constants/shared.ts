import React from "react";
import { CellContext } from "@tanstack/react-table";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  ROW_HEIGHT,
} from "@/types/shared";

export const DEMO_PROJECT_NAME = "Demo Project";
export const SNIPPET_PROJECT_NAME = "my-ai-project";
export const PROJECT_NAME_PLACEHOLDER = "PROJECT_NAME_PLACEHOLDER";
export const PLAYGROUND_PROJECT_NAME = "playground";
export const USER_FEEDBACK_NAME = "User feedback";
export const PIP_INSTALL_OPIK_COMMAND = "pip install opik";
export const INSTALL_OPIK_SECTION_TITLE =
  "1. Install Opik using pip from the command line";
export const INSTALL_SDK_SECTION_TITLE = "2. Install the SDK";

export const TRUNCATION_DISABLED_MAX_PAGE_SIZE = 10;

export const TABLE_HEADER_Z_INDEX = 2;
export const TABLE_ROW_Z_INDEX = 0;

export const ROW_HEIGHT_MAP: Record<ROW_HEIGHT, React.CSSProperties> = {
  [ROW_HEIGHT.small]: { height: "32px" },
  [ROW_HEIGHT.medium]: { height: "40px" },
  [ROW_HEIGHT.large]: { height: "160px" },
};

export const HEADER_TEXT_CLASS_MAP: Record<ROW_HEIGHT, string> = {
  [ROW_HEIGHT.small]: "comet-body-xs",
  [ROW_HEIGHT.medium]: "comet-body-s",
  [ROW_HEIGHT.large]: "comet-body-s",
};

export const CELL_TEXT_CLASS_MAP: Record<ROW_HEIGHT, string> = {
  [ROW_HEIGHT.small]: "comet-body-xs",
  [ROW_HEIGHT.medium]: "comet-body-s",
  [ROW_HEIGHT.large]: "comet-body-s",
};

export const TAG_SIZE_MAP: Record<ROW_HEIGHT, "default" | "md"> = {
  [ROW_HEIGHT.small]: "default",
  [ROW_HEIGHT.medium]: "md",
  [ROW_HEIGHT.large]: "md",
};

export const FEEDBACK_SCORE_TAG_SIZE_MAP: Record<ROW_HEIGHT, "sm" | "md"> = {
  [ROW_HEIGHT.small]: "sm",
  [ROW_HEIGHT.medium]: "sm",
  [ROW_HEIGHT.large]: "md",
};

export function getCellTagSize<T>(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  context: CellContext<any, any>,
  sizeMap: Record<ROW_HEIGHT, T>,
): T {
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  return sizeMap[rowHeight];
}

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
  [COLUMN_TYPE.category]: "justify-start",
  [COLUMN_TYPE.errors]: "justify-start",
};
