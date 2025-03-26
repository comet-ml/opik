import React from "react";

type PageBodyScrollContainerContextData = {
  scrollContainer: HTMLDivElement | null;
  tableOffset: number;
};

export const PageBodyScrollContainerContext =
  React.createContext<PageBodyScrollContainerContextData>({
    scrollContainer: null,
    tableOffset: 0,
  });

const usePageBodyScrollContainer = () => {
  const context = React.useContext(PageBodyScrollContainerContext);
  if (context === null) {
    throw new Error(
      "useContainerRef must be used within PageBodyScrollContainer!",
    );
  }
  return context;
};

export default usePageBodyScrollContainer;
