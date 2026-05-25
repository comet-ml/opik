import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Popover } from "@/ui/popover";
import { ChipOptionsResult } from "@/shared/filter-chips/types";
import { filterItems } from "./helpers";
import { AutocompleteAnchor } from "./AutocompleteAnchor";
import { AutocompleteContent } from "./AutocompleteContent";
import {
  AutocompleteContext,
  AutocompleteContextValue,
  AutocompleteInputProps,
} from "./AutocompleteContext";

interface AutocompleteProps {
  options: ChipOptionsResult;
  itemNoun: string;
  value?: string;
  onCommit: (next: string) => void;
  onPick?: (next: string) => void;
  commitOnBlur?: boolean;
  onEscape?: () => void;
  autoFocus?: boolean;
  children: React.ReactNode;
}

const AutocompleteRoot: React.FC<AutocompleteProps> = ({
  options,
  itemNoun,
  value,
  onCommit,
  onPick,
  commitOnBlur = false,
  onEscape,
  autoFocus = false,
  children,
}) => {
  const [draft, setDraft] = useState(value ?? "");
  const [focused, setFocused] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const { items, isLoading } = options;

  useEffect(() => {
    if (value !== undefined) setDraft(value);
  }, [value]);

  useEffect(() => {
    if (autoFocus) inputRef.current?.focus();
  }, [autoFocus]);

  const filtered = useMemo(() => filterItems(items, draft), [items, draft]);
  const hasQuery = draft.trim() !== "";
  const showResults = !isLoading && filtered.length > 0;
  const showNoMatch = hasQuery && !isLoading && filtered.length === 0;
  const popoverOpen = focused && (isLoading || showResults || showNoMatch);

  const commit = useCallback(
    (next: string) => {
      const trimmed = next.trim();
      if (trimmed === "") return;
      if (value !== undefined && trimmed === value.trim()) return;
      onCommit(trimmed);
    },
    [onCommit, value],
  );

  const pick = useCallback(
    (item: string) => {
      setDraft(item);
      commit(item);
      onPick?.(item);
    },
    [commit, onPick],
  );

  const inputProps = useMemo<AutocompleteInputProps>(
    () => ({
      ref: inputRef,
      value: draft,
      onChange: (event) => setDraft(event.target.value),
      onFocus: () => setFocused(true),
      onBlur: () => {
        setFocused(false);
        if (commitOnBlur) commit(draft);
      },
      onKeyDown: (event) => {
        if (event.key === "Enter" && draft.trim() !== "") {
          event.preventDefault();
          commit(draft);
          onPick?.(draft);
          inputRef.current?.blur();
        } else if (event.key === "Escape") {
          event.preventDefault();
          if (value !== undefined) setDraft(value);
          if (onEscape) onEscape();
          inputRef.current?.blur();
        }
      },
    }),
    [draft, commit, commitOnBlur, onEscape, onPick, value],
  );

  const contextValue = useMemo<AutocompleteContextValue>(
    () => ({
      draft,
      filtered,
      isLoading,
      hasQuery,
      showResults,
      showNoMatch,
      itemNoun,
      inputRef,
      inputProps,
      pick,
    }),
    [
      draft,
      filtered,
      isLoading,
      hasQuery,
      showResults,
      showNoMatch,
      itemNoun,
      inputProps,
      pick,
    ],
  );

  return (
    <AutocompleteContext.Provider value={contextValue}>
      <Popover open={popoverOpen}>{children}</Popover>
    </AutocompleteContext.Provider>
  );
};

const Autocomplete = Object.assign(AutocompleteRoot, {
  Anchor: AutocompleteAnchor,
  Content: AutocompleteContent,
});

export default Autocomplete;
