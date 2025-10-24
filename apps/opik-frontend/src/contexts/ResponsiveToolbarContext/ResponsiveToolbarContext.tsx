import React, { createContext, useContext, useState, useMemo } from "react";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

export interface ToolbarElement {
  name: string;
  size: number;
  visible: boolean;
}

interface ResponsiveToolbarContextValue {
  hasSpace: boolean;
}

const ResponsiveToolbarContext = createContext<
  ResponsiveToolbarContextValue | undefined
>(undefined);

interface ResponsiveToolbarProviderProps {
  elements: ToolbarElement[];
  extraSpace?: number;
  children: React.ReactNode;
}

export const ResponsiveToolbarProvider: React.FC<
  ResponsiveToolbarProviderProps
> = ({ elements, extraSpace = 0, children }) => {
  const [hasSpace, setHasSpace] = useState(true);

  const minWidth = useMemo(() => {
    return (
      elements.reduce((acc, e) => acc + (e.visible ? e.size : 0), 0) +
      extraSpace
    );
  }, [elements, extraSpace]);

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    setHasSpace(node.clientWidth >= minWidth);
  });

  const value = useMemo(() => ({ hasSpace }), [hasSpace]);

  return (
    <ResponsiveToolbarContext.Provider value={value}>
      <div ref={ref} className="flex-1">
        {children}
      </div>
    </ResponsiveToolbarContext.Provider>
  );
};

export const useResponsiveToolbar = (): ResponsiveToolbarContextValue => {
  const context = useContext(ResponsiveToolbarContext);
  if (!context) {
    throw new Error(
      "useResponsiveToolbar must be used within a ResponsiveToolbarProvider",
    );
  }
  return context;
};

