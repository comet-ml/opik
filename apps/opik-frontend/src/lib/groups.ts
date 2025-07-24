import uniqid from "uniqid";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { Group, Groups } from "@/types/groups";

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
