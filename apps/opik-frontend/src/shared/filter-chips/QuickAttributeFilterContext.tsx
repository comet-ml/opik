import React, { createContext, useContext } from "react";
import { JsonValue } from "@/types/shared";

export type QuickFilterSection = "metadata" | "input" | "output";

export interface QuickAttributeFilterApi {
  // Whether the given attribute can be turned into a working filter for the
  // active tab (e.g. computed/non-stored keys like trace "providers" cannot).
  canFilter: (section: QuickFilterSection, path: string) => boolean;
  // Seed a filter for the attribute and open the matching chip for editing.
  filter: (section: QuickFilterSection, path: string, value: JsonValue) => void;
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
