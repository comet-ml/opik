import React from "react";
import LoadingOverlay from "@/components/shared/LoadingOverlay/LoadingOverlay";

export type DataTableWrapperProps = {
  children: React.ReactNode;
  showLoadingOverlay?: boolean;
};

const DataTableWrapper: React.FC<DataTableWrapperProps> = ({
  children,
  showLoadingOverlay = false,
}) => {
  return (
    <div className="overflow-x-auto overflow-y-hidden rounded-md border">
      <div className="relative">
        {children}
        <LoadingOverlay isVisible={showLoadingOverlay} />
      </div>
    </div>
  );
};

export default DataTableWrapper;
