import React, { ReactNode } from "react";
import Loader from "@/components/shared/Loader/Loader";

type DataTableStateHandlerProps = {
  isLoading: boolean;
  isEmpty: boolean;
  emptyState: ReactNode;
  children: ReactNode;
};

const DataTableStateHandler: React.FC<DataTableStateHandlerProps> = ({
  isLoading,
  isEmpty,
  emptyState,
  children,
}) => {
  if (isLoading) {
    return <Loader />;
  }

  if (isEmpty) {
    return <>{emptyState}</>;
  }

  return <>{children}</>;
};

export default DataTableStateHandler;
