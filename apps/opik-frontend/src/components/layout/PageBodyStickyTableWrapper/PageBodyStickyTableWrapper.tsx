import React from "react";

type PageBodyStickyTableWrapperProps = {
  children: React.ReactNode;
};

const PageBodyStickyTableWrapper: React.FC<PageBodyStickyTableWrapperProps> = ({
  children,
}) => {
  return <div className="bg-red-500">{children}</div>;
};
export default PageBodyStickyTableWrapper;
