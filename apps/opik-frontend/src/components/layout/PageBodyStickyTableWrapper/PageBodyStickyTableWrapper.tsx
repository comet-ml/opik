import React from "react";

type PageBodyStickyTableWrapperProps = {
  children: React.ReactNode;
};

const PageBodyStickyTableWrapper: React.FC<PageBodyStickyTableWrapperProps> = ({
  children,
}) => {
  return <div>{children}</div>;
};
export default PageBodyStickyTableWrapper;
