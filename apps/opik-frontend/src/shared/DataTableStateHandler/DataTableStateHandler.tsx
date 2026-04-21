import React, { ReactNode } from "react";
import Loader from "@/shared/Loader/Loader";

type DataTableStateHandlerProps = {
  isLoading: boolean;
  isEmpty: boolean;
  emptyState: ReactNode;
  children: ReactNode;
  skeleton?: boolean;
};

const DataTableStateHandler: React.FC<DataTableStateHandlerProps> = ({
  isLoading,
  isEmpty,
  emptyState,
  children,
  skeleton = false,
}) => {
  if (isLoading && !skeleton) {
    return <Loader />;
  }

  if (isEmpty && !isLoading) {
    return <>{emptyState}</>;
  }

  return <>{children}</>;
};

export default DataTableStateHandler;
