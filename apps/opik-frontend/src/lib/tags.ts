import uniq from "lodash/uniq";

import { COLUMN_TYPE, DynamicColumn } from "@/types/shared";

export type TagUpdateFields = {
  tagsToAdd?: string[];
  tagsToRemove?: string[];
};

export const TAG_COLUMN_PREFIX = "tags.";

export const getTagColumnId = (tag: string) => `${TAG_COLUMN_PREFIX}${tag}`;

export const buildDynamicTagColumns = (tags: string[]): DynamicColumn[] => {
  return uniq(tags)
    .filter(Boolean)
    .sort((tag1, tag2) => tag1.localeCompare(tag2))
    .map<DynamicColumn>((tag) => ({
      id: getTagColumnId(tag),
      label: tag,
      columnType: COLUMN_TYPE.string,
    }));
};

export const buildTagUpdatePayload = <T extends TagUpdateFields>(
  update: T,
): Record<string, unknown> => {
  const { tagsToAdd, tagsToRemove, ...rest } = update;
  const payload: Record<string, unknown> = { ...rest };
  if (tagsToAdd !== undefined) payload.tags_to_add = tagsToAdd;
  if (tagsToRemove !== undefined) payload.tags_to_remove = tagsToRemove;
  return payload;
};
