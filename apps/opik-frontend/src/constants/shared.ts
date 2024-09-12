import { CELL_VERTICAL_ALIGNMENT, ROW_HEIGHT } from "@/types/shared";

export const ROW_HEIGHT_MAP = {
  [ROW_HEIGHT.small]: "h-14",
  [ROW_HEIGHT.medium]: "h-[120px]",
  [ROW_HEIGHT.large]: "h-[296px]",
};

export const CELL_VERTICAL_ALIGNMENT_MAP = {
  [CELL_VERTICAL_ALIGNMENT.start]: "items-start",
  [CELL_VERTICAL_ALIGNMENT.center]: "items-center",
  [CELL_VERTICAL_ALIGNMENT.end]: "items-end",
};
