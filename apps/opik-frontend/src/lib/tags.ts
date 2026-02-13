export type TagUpdateFields = {
  tagsToAdd?: string[];
  tagsToRemove?: string[];
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
