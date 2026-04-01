import React, { useCallback, useRef } from "react";

export type DataTableWrapperProps = {
  children: React.ReactNode;
};

const DataTableWrapper: React.FC<DataTableWrapperProps> = ({ children }) => {
  const rafId = useRef(0);
  const wasScrolled = useRef(false);

  const handleScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    const target = e.currentTarget;
    cancelAnimationFrame(rafId.current);
    rafId.current = requestAnimationFrame(() => {
      const isScrolled = target.scrollLeft > 0;
      if (isScrolled !== wasScrolled.current) {
        wasScrolled.current = isScrolled;
        target.toggleAttribute("data-scrolled-right", isScrolled);
      }
    });
  }, []);

  return (
    <div
      className="overflow-x-auto overflow-y-hidden rounded-md border"
      data-table-scroll-container
      onScroll={handleScroll}
    >
      {children}
    </div>
  );
};

export default DataTableWrapper;
