import React from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";

import DashboardWidgetHeader from "./DashboardWidgetHeader";
import DashboardWidgetActions from "./DashboardWidgetActions";
import DashboardWidgetActionsMenu from "./DashboardWidgetActionsMenu";
import DashboardWidgetContent from "./DashboardWidgetContent";
import DashboardWidgetPreviewContent from "./DashboardWidgetPreviewContent";
import DashboardWidgetPreviewHeader from "./DashboardWidgetPreviewHeader";
import DashboardWidgetEmptyState from "./DashboardWidgetEmptyState";
import DashboardWidgetErrorState from "./DashboardWidgetErrorState";
import DashboardWidgetDragHandle from "./DashboardWidgetDragHandle";
import DashboardWidgetDisabledState from "./DashboardWidgetDisabledState";

type DashboardWidgetRootProps = {
  children: React.ReactNode;
  className?: string;
};

const DashboardWidgetRoot: React.FunctionComponent<
  DashboardWidgetRootProps
> = ({ children, className }) => {
  return (
    <div className="group/widget h-full">
      <Card
        className={cn(
          "flex h-full flex-col gap-2 rounded-md px-2 pb-2 pt-1",
          className,
        )}
      >
        {children}
      </Card>
    </div>
  );
};

type DashboardWidgetComponents = typeof DashboardWidgetRoot & {
  Header: typeof DashboardWidgetHeader;
  Actions: typeof DashboardWidgetActions;
  ActionsMenu: typeof DashboardWidgetActionsMenu;
  Content: typeof DashboardWidgetContent;
  PreviewContent: typeof DashboardWidgetPreviewContent;
  PreviewHeader: typeof DashboardWidgetPreviewHeader;
  EmptyState: typeof DashboardWidgetEmptyState;
  ErrorState: typeof DashboardWidgetErrorState;
  DragHandle: typeof DashboardWidgetDragHandle;
  DisabledState: typeof DashboardWidgetDisabledState;
};

const DashboardWidget = DashboardWidgetRoot as DashboardWidgetComponents;

DashboardWidget.Header = DashboardWidgetHeader;
DashboardWidget.Actions = DashboardWidgetActions;
DashboardWidget.ActionsMenu = DashboardWidgetActionsMenu;
DashboardWidget.Content = DashboardWidgetContent;
DashboardWidget.PreviewContent = DashboardWidgetPreviewContent;
DashboardWidget.PreviewHeader = DashboardWidgetPreviewHeader;
DashboardWidget.EmptyState = DashboardWidgetEmptyState;
DashboardWidget.ErrorState = DashboardWidgetErrorState;
DashboardWidget.DragHandle = DashboardWidgetDragHandle;
DashboardWidget.DisabledState = DashboardWidgetDisabledState;

export default DashboardWidget;
