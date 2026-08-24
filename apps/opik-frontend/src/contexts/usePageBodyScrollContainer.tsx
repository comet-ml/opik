import React from "react";
import noop from "lodash/noop";

type PageBodyScrollContainerContextData = {
  scrollContainer: HTMLDivElement | null;
  horizontalScrollContainer: HTMLElement | null;
  registerHorizontalScrollContainer: (element: HTMLElement | null) => void;
  tableOffset: number;
  recalculateOffsets: () => void;
};

export const PageBodyScrollContainerContext =
  React.createContext<PageBodyScrollContainerContextData>({
    scrollContainer: null,
    horizontalScrollContainer: null,
    registerHorizontalScrollContainer: noop,
    tableOffset: 0,
    recalculateOffsets: noop,
  });

const usePageBodyScrollContainer = () => {
  const context = React.useContext(PageBodyScrollContainerContext);
  if (context === null) {
    throw new Error(
      "usePageBodyScrollContainer must be used within PageBodyScrollContainer!",
    );
  }
  return context;
};

export default usePageBodyScrollContainer;
