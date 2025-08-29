export const DELETED_DATASET_ID = "deleted_dataset_id";
export const DEFAULT_GROUPS_PER_PAGE = 5;
export const GROUPING_COLUMN = "virtual_dataset_id";

export const MAX_GROUP_LEVELS = 5;
export const DEFAULT_ITEMS_PER_GROUP = 100;
export const DELETED_DATASET_LABEL = "__DELETED";
export const GROUPING_KEY = "$__grouping__";
export const GROUPING_ROW_PREFIX = "$__grouping_row__";
export const GROUP_ID_SEPARATOR = ">";

export enum GROUP_ROW_TYPE {
  MORE = "MORE",
  PENDING = "PENDING",
  ERROR = "ERROR",
}

export const GROUP_ROW_PREFIX_MAP = {
  [GROUP_ROW_TYPE.MORE]: `${GROUPING_ROW_PREFIX}more__`,
  [GROUP_ROW_TYPE.PENDING]: `${GROUPING_ROW_PREFIX}pending__`,
  [GROUP_ROW_TYPE.ERROR]: `${GROUPING_ROW_PREFIX}error__`,
} as const;
