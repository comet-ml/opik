import React, { createContext, useContext } from "react";
import { JsonValue } from "@/types/shared";

export type QuickFilterSection = "metadata" | "input" | "output";

export interface QuickAttributeFilterApi {
  // Whether the given attribute can be turned into a working filter for the
  // active tab (e.g. computed/non-stored keys like trace "providers" cannot).
  canFilter: (section: QuickFilterSection, path: string) => boolean;
  // Seed a filter for the attribute and open the matching chip for editing.
  filter: (section: QuickFilterSection, path: string, value: JsonValue) => void;
  // Labels for the inline affordance. They name the destination, because the
  // click can also move the table from the Traces view to the Spans view.
  hintText: string;
  appliedText: string;
}

// The details panel shows the selected span, or the trace — and it falls back
// to the trace when the selected span is not among the loaded ones. Only the
// panel knows which entity is on screen, so the page provides one api per
// entity and the panel narrows it to the right one.
export type QuickFilterEntity = "trace" | "span";

export type QuickAttributeFilterFactory = (
  entity: QuickFilterEntity,
) => QuickAttributeFilterApi;

const QuickAttributeFilterFactoryContext = createContext<
  QuickAttributeFilterFactory | undefined
>(undefined);

export const QuickAttributeFilterFactoryProvider: React.FC<{
  value: QuickAttributeFilterFactory | undefined;
  children: React.ReactNode;
}> = ({ value, children }) => (
  <QuickAttributeFilterFactoryContext.Provider value={value}>
    {children}
  </QuickAttributeFilterFactoryContext.Provider>
);

export const useQuickAttributeFilterFactory = ():
  | QuickAttributeFilterFactory
  | undefined => useContext(QuickAttributeFilterFactoryContext);

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
