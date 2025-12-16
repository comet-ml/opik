import React from "react";
import LoadingOverlay from "@/components/shared/LoadingOverlay/LoadingOverlay";

export const TABLE_WRAPPER_ATTRIBUTE = "data-table-wrapper";

type PageBodyStickyTableWrapperProps = {
  children: React.ReactNode;
  showLoadingOverlay?: boolean;
};

const PageBodyStickyTableWrapper: React.FC<PageBodyStickyTableWrapperProps> = ({
  children,
  showLoadingOverlay = false,
}) => {
  return (
    <div
      className="comet-sticky-table border-b"
      {...{
        [TABLE_WRAPPER_ATTRIBUTE]: "",
      }}
    >
      <div className="relative">
        {children}
        <LoadingOverlay isVisible={showLoadingOverlay} />
      </div>
    </div>
  );
};
export default PageBodyStickyTableWrapper;
