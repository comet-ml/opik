type Taggable = { tags?: string[] | null };

export const extractTagsFromItems = (
  items: ReadonlyArray<Taggable> | null | undefined,
): string[] => {
  const set = new Set<string>();
  for (const item of items ?? []) {
    for (const tag of item?.tags ?? []) {
      const trimmed = tag?.trim();
      if (trimmed) set.add(trimmed);
    }
  }
  return [...set].sort();
};

export const filterTagsByQuery = (
  tags: ReadonlyArray<string>,
  query: string | null | undefined,
): string[] => {
  if (!query) return [...tags];
  const needle = query.toLowerCase();
  return tags.filter((tag) => tag.toLowerCase().includes(needle));
};
