import React from "react";

export type DataTableWrapperProps = {
  children: React.ReactNode;
};

const DataTableWrapper: React.FC<DataTableWrapperProps> = ({ children }) => {
  return (
    <div className="overflow-x-auto overflow-y-hidden rounded-md border">
      {children}
    </div>
  );
};

export default DataTableWrapper;
