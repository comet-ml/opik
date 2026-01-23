import React from "react";

export type DataTableWrapperProps = {
  children: React.ReactNode;
  showLoadingOverlay?: boolean;
};

const DataTableWrapper: React.FC<DataTableWrapperProps> = ({
  children,
  showLoadingOverlay = false,
}) => {
  return (
    <div className="relative overflow-x-auto overflow-y-hidden rounded-md border">
      {children}
      {showLoadingOverlay && (
        <div className="duration-[1500ms] absolute inset-0 z-20 animate-pulse bg-background/70 ease-in-out" />
      )}
    </div>
  );
};

export default DataTableWrapper;
