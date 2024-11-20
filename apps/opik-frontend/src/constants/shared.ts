import { CELL_VERTICAL_ALIGNMENT, ROW_HEIGHT } from "@/types/shared";

export const TABLE_HEADER_Z_INDEX = 2;
export const TABLE_ROW_Z_INDEX = 0;

export const ROW_HEIGHT_MAP = {
  [ROW_HEIGHT.small]: "h-11",
  [ROW_HEIGHT.medium]: "h-24",
  [ROW_HEIGHT.large]: "h-[296px]",
};

export const CELL_VERTICAL_ALIGNMENT_MAP = {
  [CELL_VERTICAL_ALIGNMENT.start]: "items-start",
  [CELL_VERTICAL_ALIGNMENT.center]: "items-center",
  [CELL_VERTICAL_ALIGNMENT.end]: "items-end",
};
