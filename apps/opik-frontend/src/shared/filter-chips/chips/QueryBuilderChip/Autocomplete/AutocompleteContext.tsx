import React, { createContext, useContext } from "react";

export interface AutocompleteInputProps {
  ref: React.RefObject<HTMLInputElement>;
  value: string;
  onChange: (event: React.ChangeEvent<HTMLInputElement>) => void;
  onFocus: () => void;
  onBlur: () => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
}

export interface AutocompleteContextValue {
  draft: string;
  filtered: string[];
  isLoading: boolean;
  hasQuery: boolean;
  showResults: boolean;
  showNoMatch: boolean;
  itemNoun: string;
  inputRef: React.RefObject<HTMLInputElement>;
  inputProps: AutocompleteInputProps;
  pick: (value: string) => void;
}

export const AutocompleteContext =
  createContext<AutocompleteContextValue | null>(null);

export const useAutocompleteContext = (): AutocompleteContextValue => {
  const ctx = useContext(AutocompleteContext);
  if (!ctx) {
    throw new Error(
      "Autocomplete subcomponents must be rendered inside <Autocomplete>",
    );
  }
  return ctx;
};
