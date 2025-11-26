import DashboardWidgetRoot from "./DashboardWidgetRoot";
import DashboardWidgetHeader from "./DashboardWidgetHeader";
import DashboardWidgetActions from "./DashboardWidgetActions";
import DashboardWidgetActionsMenu from "./DashboardWidgetActionsMenu";
import DashboardWidgetContent from "./DashboardWidgetContent";
import DashboardWidgetPreviewContent from "./DashboardWidgetPreviewContent";
import DashboardWidgetEmptyState from "./DashboardWidgetEmptyState";
import DashboardWidgetErrorState from "./DashboardWidgetErrorState";
import DashboardWidgetDragHandle from "./DashboardWidgetDragHandle";

type DashboardWidgetComponents = typeof DashboardWidgetRoot & {
  Header: typeof DashboardWidgetHeader;
  Actions: typeof DashboardWidgetActions;
  ActionsMenu: typeof DashboardWidgetActionsMenu;
  Content: typeof DashboardWidgetContent;
  PreviewContent: typeof DashboardWidgetPreviewContent;
  EmptyState: typeof DashboardWidgetEmptyState;
  ErrorState: typeof DashboardWidgetErrorState;
  DragHandle: typeof DashboardWidgetDragHandle;
};

const DashboardWidget = DashboardWidgetRoot as DashboardWidgetComponents;

DashboardWidget.Header = DashboardWidgetHeader;
DashboardWidget.Actions = DashboardWidgetActions;
DashboardWidget.ActionsMenu = DashboardWidgetActionsMenu;
DashboardWidget.Content = DashboardWidgetContent;
DashboardWidget.PreviewContent = DashboardWidgetPreviewContent;
DashboardWidget.EmptyState = DashboardWidgetEmptyState;
DashboardWidget.ErrorState = DashboardWidgetErrorState;
DashboardWidget.DragHandle = DashboardWidgetDragHandle;

export default DashboardWidget;
