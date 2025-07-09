import { useMemo, useEffect, useCallback, useState } from "react";
import { Extension } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import {
  search,
  openSearchPanel,
  closeSearchPanel,
  SearchQuery,
  setSearchQuery,
  findNext,
  findPrevious,
  getSearchQuery,
} from "@codemirror/search";

interface UseCodeMirrorSearchOptions {
  view: EditorView | null;
  searchValue?: string;
  caseSensitive?: boolean;
  regexp?: boolean;
  data?: object;
}

interface UseCodeMirrorSearchReturn {
  extension: Extension;
  findNext: () => void;
  findPrev: () => void;
  currentMatchIndex: number;
  totalMatches: number;
}

export const useCodeMirrorSearch = (
  options: UseCodeMirrorSearchOptions,
): UseCodeMirrorSearchReturn => {
  const [searchState, setSearchState] = useState({
    totalMatches: 0,
    currentMatchIndex: 0,
  });
  const {
    view,
    searchValue = "",
    caseSensitive = false,
    regexp = false,
    data,
  } = options;

  const searchConfig = useMemo(() => {
    return search({
      top: true,
      caseSensitive,
      regexp,
      wholeWord: false,
      scrollToMatch: (range) => EditorView.scrollIntoView(range),
      createPanel: () => ({
        dom: document.createElement("div"),
      }),
    });
  }, [caseSensitive, regexp]);

  const extension = useMemo(() => searchConfig, [searchConfig]);

  const updateSearchState = useCallback(() => {
    if (!view) return;

    const searchQuery = getSearchQuery(view.state);
    const cursor = searchQuery.getCursor(view.state);

    const counter = { count: 0, current: 1 };
    const { from, to } = view.state.selection.main;

    let item = cursor.next();
    while (!item.done) {
      if (item.value.from === from && item.value.to === to) {
        counter.current = counter.count + 1;
      }

      item = cursor.next();
      counter.count++;
    }

    setSearchState({
      totalMatches: counter.count,
      currentMatchIndex: counter.current,
    });
  }, [view]);

  const findNextOccurrence = useCallback(() => {
    if (!view) return;

    findNext(view);
    updateSearchState();
  }, [view, updateSearchState]);

  const findPreviousOccurrence = useCallback(() => {
    if (!view) return;

    findPrevious(view);
    updateSearchState();
  }, [view, updateSearchState]);

  useEffect(() => {
    if (!view) return;

    if (!searchValue?.trim()) {
      closeSearchPanel(view);
      setSearchState({
        totalMatches: 0,
        currentMatchIndex: 0,
      });

      return;
    }

    const searchQuery = new SearchQuery({
      search: searchValue,
      caseSensitive,
      regexp,
      wholeWord: false,
      replace: "",
    });

    view.dispatch({
      selection: { anchor: 0, head: 0 },
      scrollIntoView: false,
    });

    openSearchPanel(view);
    view.dispatch({
      effects: setSearchQuery.of(searchQuery),
    });
    findNext(view);

    updateSearchState();
  }, [view, searchValue, caseSensitive, regexp, data, updateSearchState]);

  return {
    extension,
    findNext: findNextOccurrence,
    findPrev: findPreviousOccurrence,
    currentMatchIndex: searchState.currentMatchIndex,
    totalMatches: searchState.totalMatches,
  };
};
