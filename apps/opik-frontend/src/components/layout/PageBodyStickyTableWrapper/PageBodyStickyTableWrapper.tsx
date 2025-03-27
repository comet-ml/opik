import React from "react";

export const TABLE_WRAPPER_ATTRIBUTE = "data-table-wrapper";

type PageBodyStickyTableWrapperProps = {
  children: React.ReactNode;
};

const PageBodyStickyTableWrapper: React.FC<PageBodyStickyTableWrapperProps> = ({
  children,
}) => {
  return (
    <div
      className="comet-sticky-table border-b"
      {...{
        [TABLE_WRAPPER_ATTRIBUTE]: "",
      }}
    >
      {children}
    </div>
  );
};
export default PageBodyStickyTableWrapper;
