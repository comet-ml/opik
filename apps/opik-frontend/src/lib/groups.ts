import uniqid from "uniqid";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { FlattenGroup, Group, Groups } from "@/types/groups";
import { GROUP_ID_SEPARATOR } from "@/constants/groups";

export const isGroupValid = (group: Group) => {
  const hasField = group.field !== "";

  const hasKey =
    group.type === COLUMN_TYPE.dictionary ||
    group.type === COLUMN_TYPE.numberDictionary
      ? group.key !== ""
      : true;

  return hasField && hasKey;
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

  const groupIdParts = group.id.split(GROUP_ID_SEPARATOR);
  return groupIdParts.every((_, index) => {
    const parentId = groupIdParts.slice(0, index + 1).join(GROUP_ID_SEPARATOR);
    return expandedMap?.[parentId];
  });
};

export const generateExpandedMapForAllGroups = (
  flattenGroups: FlattenGroup[],
): Record<string, boolean> => {
  const expandedMap: Record<string, boolean> = {};

  flattenGroups.forEach((group) => {
    // Mark the group itself as expanded
    expandedMap[group.id] = true;

    // Mark all parent groups as expanded
    const groupIdParts = group.id.split(GROUP_ID_SEPARATOR);
    groupIdParts.forEach((_, index) => {
      const parentId = groupIdParts
        .slice(0, index + 1)
        .join(GROUP_ID_SEPARATOR);
      expandedMap[parentId] = true;
    });
  });

  return expandedMap;
};
