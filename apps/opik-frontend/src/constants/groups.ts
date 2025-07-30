import { GroupingState } from "@tanstack/react-table";

// TODO lala check all constants and remove unused ones

export const DELETED_DATASET_ID = "deleted_dataset_id";
export const DELETED_DATASET_LABEL = "__DELETED";
export const DEFAULT_GROUPS_PER_PAGE = 5;
export const DEFAULT_ITEMS_PER_GROUP = 100;
export const GROUPING_COLUMN = "virtual_dataset_id";
export const GROUPING_KEY = "$__grouping__";
export const MORE_ROW_PREFIX = "$__more__";
export const PENDING_ROW_PREFIX = "$__pending__";
export const GROUP_ID_SEPARATOR = ">";

export const GROUPING_CONFIG = {
  groupedColumnMode: false as const,
  grouping: [GROUPING_COLUMN] as GroupingState,
};
