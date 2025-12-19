import { useMemo } from "react";

interface UseVisibleTagsReturn {
  sortedItems: string[];
  visibleItems: string[];
  hiddenItems: string[];
  hasMoreItems: boolean;
  remainingCount: number;
}

const sortTags = (tags: string[] | null | undefined): string[] => {
  if (!tags || !Array.isArray(tags)) return [];
  return [...tags].sort();
};

export const useVisibleTags = (
  tags: string[] | null | undefined,
  maxVisible: number = 3,
): UseVisibleTagsReturn => {
  const sortedItems = useMemo(() => sortTags(tags), [tags]);
  const visibleItems = useMemo(
    () => sortedItems.slice(0, maxVisible),
    [sortedItems, maxVisible],
  );
  const hiddenItems = useMemo(
    () => sortedItems.slice(maxVisible),
    [sortedItems, maxVisible],
  );
  const hasMoreItems = sortedItems.length > maxVisible;
  const remainingCount = Math.max(0, sortedItems.length - maxVisible);

  return {
    sortedItems,
    visibleItems,
    hiddenItems,
    hasMoreItems,
    remainingCount,
  };
};
