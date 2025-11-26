import React from "react";

type DashboardWidgetPreviewContentProps = {
  children: React.ReactNode;
};

const DashboardWidgetPreviewContent: React.FC<
  DashboardWidgetPreviewContentProps
> = ({ children }) => {
  return <div className="h-full">{children}</div>;
};

export default DashboardWidgetPreviewContent;
