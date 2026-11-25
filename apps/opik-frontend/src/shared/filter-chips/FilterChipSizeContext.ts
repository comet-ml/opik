import { createContext, useContext } from "react";

export type FilterChipSize = "sm" | "md";

export const FILTER_CHIP_HEIGHT_CLASS: Record<FilterChipSize, string> = {
  sm: "h-6",
  md: "h-8",
};

const FilterChipSizeContext = createContext<FilterChipSize>("sm");

export const FilterChipSizeProvider = FilterChipSizeContext.Provider;

export const useFilterChipHeightClass = () =>
  FILTER_CHIP_HEIGHT_CLASS[useContext(FilterChipSizeContext)];
