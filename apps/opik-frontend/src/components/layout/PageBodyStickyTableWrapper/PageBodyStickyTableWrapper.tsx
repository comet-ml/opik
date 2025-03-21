import React from "react";

type PageBodyStickyTableWrapperProps = {
  children: React.ReactNode;
};

const PageBodyStickyTableWrapper: React.FC<PageBodyStickyTableWrapperProps> = ({
  children,
}) => {
  return <div className="comet-sticky-table border-b">{children}</div>;
};
export default PageBodyStickyTableWrapper;
