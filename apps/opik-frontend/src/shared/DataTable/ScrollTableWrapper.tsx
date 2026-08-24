import React from "react";

import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import DataTableWrapper from "@/shared/DataTable/DataTableWrapper";

type ScrollTableWrapperProps = {
  children: React.ReactNode;
};

const ScrollTableWrapper: React.FC<ScrollTableWrapperProps> = ({
  children,
}) => {
  const { registerHorizontalScrollContainer } = usePageBodyScrollContainer();

  return (
    <DataTableWrapper scrollRef={registerHorizontalScrollContainer}>
      {children}
    </DataTableWrapper>
  );
};

export default ScrollTableWrapper;
