import { useMemo } from "react";

interface UseVisibleTagsReturn {
  sortedItems: string[];
  visibleItems: string[];
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
  return useMemo(() => {
    const sortedItems = sortTags(tags);
    const visibleItems = sortedItems.slice(0, maxVisible);
    const hasMoreItems = sortedItems.length > maxVisible;
    const remainingCount = Math.max(0, sortedItems.length - maxVisible);

    return {
      sortedItems,
      visibleItems,
      hasMoreItems,
      remainingCount,
    };
  }, [tags, maxVisible]);
};
