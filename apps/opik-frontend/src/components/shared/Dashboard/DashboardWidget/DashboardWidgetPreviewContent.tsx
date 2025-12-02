import React from "react";

type DashboardWidgetPreviewContentProps = {
  children: React.ReactNode;
};

const DashboardWidgetPreviewContent: React.FunctionComponent<
  DashboardWidgetPreviewContentProps
> = ({ children }) => {
  return <div className="h-full">{children}</div>;
};

export default DashboardWidgetPreviewContent;
