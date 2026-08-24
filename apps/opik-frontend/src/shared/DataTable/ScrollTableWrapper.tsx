import React from "react";

import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import DataTableWrapper, {
  DataTableWrapperProps,
} from "@/shared/DataTable/DataTableWrapper";

const ScrollTableWrapper: React.FC<DataTableWrapperProps> = ({ children }) => {
  const { registerHorizontalScrollContainer } = usePageBodyScrollContainer();

  return (
    <DataTableWrapper scrollRef={registerHorizontalScrollContainer}>
      {children}
    </DataTableWrapper>
  );
};

export default ScrollTableWrapper;
