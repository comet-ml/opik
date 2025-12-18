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
      className="comet-sticky-table isolate relative border-b"
      {...{
        [TABLE_WRAPPER_ATTRIBUTE]: "",
      }}
    >
        {children}
        <LoadingOverlay isVisible={showLoadingOverlay} />
    </div>
  );
};
export default PageBodyStickyTableWrapper;
