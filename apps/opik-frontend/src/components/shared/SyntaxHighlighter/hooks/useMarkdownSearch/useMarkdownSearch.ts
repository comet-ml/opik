import { useMemo, useCallback, useEffect, useRef, useState } from "react";
import { createRemarkSearchPlugin, highlightPlainText } from "./plugin";
import {
  MatchIndex,
  VisitorNode,
} from "@/components/shared/SyntaxHighlighter/types";
import { scrollToMatchByIndex } from "@/components/shared/SyntaxHighlighter/utils";

export type UseMarkdownSearchOptions = {
  searchValue?: string;
  container: HTMLElement | null;
};

export type UseMarkdownSearchReturn = {
  searchPlugin: (tree: VisitorNode) => void;
  searchPlainText: (text: string) => React.ReactNode[];
  findNext: () => void;
  findPrev: () => void;
};

type SearchState = {
  currentMatchIndex: number;
  totalMatches: number;
};

const getNextIndex = (currentIndex: number, totalMatches: number): number => {
  return currentIndex >= totalMatches - 1 ? 0 : currentIndex + 1;
};

const getPrevIndex = (currentIndex: number, totalMatches: number): number => {
  return currentIndex <= 0 ? totalMatches - 1 : currentIndex - 1;
};

export const useMarkdownSearch = ({
  searchValue,
  container,
}: UseMarkdownSearchOptions): UseMarkdownSearchReturn => {
  const [searchState, setSearchState] = useState<SearchState>({
    currentMatchIndex: 0,
    totalMatches: 0,
  });
  const { currentMatchIndex } = searchState;
  const matchIndexRef = useRef<MatchIndex>({ value: 0 });

  const searchPlugin = useMemo(() => {
    return () => (tree: VisitorNode) => {
      if (!searchValue?.trim()) {
        setSearchState({ currentMatchIndex: 0, totalMatches: 0 });
        return;
      }

      matchIndexRef.current.value = 0;

      const result = createRemarkSearchPlugin(
        searchValue,
        matchIndexRef.current,
        currentMatchIndex,
      )(tree);

      setSearchState((prev) => ({
        ...prev,
        totalMatches: matchIndexRef.current.value,
      }));

      return result;
    };
  }, [searchValue, currentMatchIndex]);

  const searchPlainText = useCallback(
    (text: string) => {
      if (!searchValue?.trim()) {
        setSearchState({ currentMatchIndex: 0, totalMatches: 0 });
        return [text];
      }

      matchIndexRef.current.value = 0;

      const result = highlightPlainText(
        text,
        searchValue,
        matchIndexRef.current,
        currentMatchIndex,
      );

      setSearchState((prev) => ({
        ...prev,
        totalMatches: matchIndexRef.current.value,
      }));

      return result;
    },
    [searchValue, currentMatchIndex],
  );

  useEffect(() => {
    if (!container || !searchValue?.trim()) {
      return;
    }

    setSearchState((prev) => ({ ...prev, currentMatchIndex: 0 }));
    scrollToMatchByIndex(container, 0);
  }, [searchValue, container]);

  const findNext = useCallback((): void => {
    setSearchState((prev) => {
      if (prev.totalMatches === 0) return prev;

      const nextIndex = getNextIndex(prev.currentMatchIndex, prev.totalMatches);

      scrollToMatchByIndex(container, nextIndex);
      return { ...prev, currentMatchIndex: nextIndex };
    });
  }, [container]);

  const findPrev = useCallback((): void => {
    setSearchState((prev) => {
      if (prev.totalMatches === 0) return prev;

      const prevIndex = getPrevIndex(prev.currentMatchIndex, prev.totalMatches);

      scrollToMatchByIndex(container, prevIndex);
      return { ...prev, currentMatchIndex: prevIndex };
    });
  }, [container]);

  return {
    searchPlugin,
    searchPlainText,
    findNext,
    findPrev,
  };
};
