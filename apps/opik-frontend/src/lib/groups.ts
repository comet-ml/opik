import uniqid from "uniqid";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
} from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { FlattenGroup, Group, Groups } from "@/types/groups";
import {
  GROUP_ID_SEPARATOR,
  GROUP_ROW_TYPE,
  GROUP_ROW_PREFIX_MAP,
  GROUPING_ROW_PREFIX,
  GROUPING_KEY,
} from "@/constants/groups";

export const isGroupValid = (group: Group) => {
  const hasField = group.field !== "";

  const hasKey =
    group.type === COLUMN_TYPE.dictionary ||
    group.type === COLUMN_TYPE.numberDictionary
      ? group.key !== ""
      : true;

  const hasError = group.error && group.error.length > 0;

  return hasField && hasKey && !hasError;
};

export const createEmptyGroup = () => {
  return {
    id: uniqid(),
    field: "",
    type: "",
    direction: SORT_DIRECTION.ASC,
    key: "",
  } as Group;
};

export const processGroups = (groups?: Groups) => {
  const retVal: {
    groups?: string;
  } = {};
  if (groups && groups.length > 0) {
    retVal.groups = JSON.stringify(groups);
  }

  return retVal;
};

export const isGroupFullyExpanded = (
  group: FlattenGroup,
  expandedMap?: Record<string, boolean>,
) => {
  if (!expandedMap?.[group.id]) return false;

  const groupIdParts = group.id.split(GROUP_ID_SEPARATOR).slice(0, -1);
  return groupIdParts.every((_, idx) => {
    const parentId = groupIdParts.slice(0, idx + 1).join(GROUP_ID_SEPARATOR);
    return expandedMap?.[parentId];
  });
};

export const generateAutoExpandMap = (
  flattenGroups: FlattenGroup[],
  maxExpandedDeepestGroups: number = Number.MAX_SAFE_INTEGER,
): Record<string, boolean> => {
  const expandedMap: Record<string, boolean> = {};
  const maxLevel = Math.max(...flattenGroups.map((group) => group.level), 0);

  if (flattenGroups.length === 0) return expandedMap;

  flattenGroups
    .filter(
      (group) =>
        group.level === maxLevel && group.id.startsWith(flattenGroups[0].id),
    )
    .forEach((group, i) => {
      const groupIdParts = group.id.split(GROUP_ID_SEPARATOR).slice(0, -1);
      groupIdParts.forEach((_, idx) => {
        const parentId = groupIdParts
          .slice(0, idx + 1)
          .join(GROUP_ID_SEPARATOR);
        expandedMap[parentId] = true;
      });

      if (i < maxExpandedDeepestGroups) {
        expandedMap[group.id] = true;
      }
    });

  return expandedMap;
};

export const buildRowId = (rowType: GROUP_ROW_TYPE, id: string) => {
  return `${GROUP_ROW_PREFIX_MAP[rowType]}${id}`;
};

export const extractIdFromRowId = (rowType: GROUP_ROW_TYPE, rowId: string) => {
  return rowId.replace(GROUP_ROW_PREFIX_MAP[rowType], "");
};

export const checkIsRowType = (id: string, rowType: GROUP_ROW_TYPE) =>
  id.startsWith(GROUP_ROW_PREFIX_MAP[rowType]);

export const checkIsGroupRowType = (id: string) =>
  id.startsWith(GROUPING_ROW_PREFIX);

export const buildGroupFieldName = (group: Group) =>
  `${GROUPING_KEY}_${group.field}_${group.key}`.replaceAll(".", "_");

export const buildGroupFieldNameForMeta = (group: Group) =>
  `${buildGroupFieldName(group)}_meta`;

export const buildGroupFieldId = (group: Group, value: string) =>
  `${buildGroupFieldName(group)}:${value}`;

export const calculateGroupLabel = (group?: Group) => {
  if (!group) return "";

  switch (group.field) {
    case COLUMN_DATASET_ID:
      return "Dataset";
    case COLUMN_METADATA_ID:
      return `config.${group.key}`;
    default:
      return group.field + (group.key ? ` (${group.key})` : "");
  }
};
