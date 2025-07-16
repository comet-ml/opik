import { useMemo, useEffect, useCallback } from "react";
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
} from "@codemirror/search";
import { CodeOutput } from "@/components/shared/SyntaxHighlighter/types";

interface UseCodeMirrorSearchOptions {
  view: EditorView | null;
  searchValue?: string;
  caseSensitive?: boolean;
  regexp?: boolean;
  codeOutput?: CodeOutput;
}

interface UseCodeMirrorSearchReturn {
  extension: Extension;
  findNext: () => void;
  findPrev: () => void;
  initSearch: (view: EditorView, searchValue?: string) => void;
}

export const useCodeMirrorSearch = (
  options: UseCodeMirrorSearchOptions,
): UseCodeMirrorSearchReturn => {
  const {
    view,
    searchValue = "",
    caseSensitive = false,
    regexp = false,
    codeOutput,
  } = options;
  const trimmedSearchValue = searchValue.trim();

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

  const findNextOccurrence = useCallback(() => {
    if (!view || !trimmedSearchValue) return;

    findNext(view);
  }, [view, trimmedSearchValue]);

  const findPreviousOccurrence = useCallback(() => {
    if (!view || !trimmedSearchValue) return;

    findPrevious(view);
  }, [view, trimmedSearchValue]);

  const initSearch = useCallback((view: EditorView, searchValue?: string) => {
    if (!searchValue?.trim()) return;

    const searchQuery = new SearchQuery({
      search: searchValue,
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
  }, []);

  useEffect(() => {
    if (!view) return;

    if (!searchValue.trim()) {
      closeSearchPanel(view);
      view.dispatch({
        selection: { anchor: 0, head: 0 },
        scrollIntoView: false,
      });

      return;
    }

    initSearch(view, searchValue);
  }, [view, searchValue, codeOutput, initSearch]);

  return {
    extension,
    findNext: findNextOccurrence,
    findPrev: findPreviousOccurrence,
    initSearch,
  };
};
