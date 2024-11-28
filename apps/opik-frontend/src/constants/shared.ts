import { CELL_VERTICAL_ALIGNMENT, ROW_HEIGHT } from "@/types/shared";
import React from "react";

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
