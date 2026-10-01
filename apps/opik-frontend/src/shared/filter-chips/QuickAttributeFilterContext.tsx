import React, { createContext, useContext } from "react";
import { JsonValue } from "@/types/shared";

export type QuickFilterSection = "metadata" | "input" | "output";

export interface QuickAttributeFilterApi {
  canFilter: (section: QuickFilterSection, path: string) => boolean;
  filter: (section: QuickFilterSection, path: string, value: JsonValue) => void;
  hint?: string;
}

const QuickAttributeFilterContext = createContext<
  QuickAttributeFilterApi | undefined
>(undefined);

export const QuickAttributeFilterProvider: React.FC<{
  value: QuickAttributeFilterApi | undefined;
  children: React.ReactNode;
}> = ({ value, children }) => (
  <QuickAttributeFilterContext.Provider value={value}>
    {children}
  </QuickAttributeFilterContext.Provider>
);

export const useQuickAttributeFilter = ():
  | QuickAttributeFilterApi
  | undefined => useContext(QuickAttributeFilterContext);
