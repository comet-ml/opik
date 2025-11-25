import DashboardWidgetRoot from "./DashboardWidgetRoot";
import DashboardWidgetHeader from "./DashboardWidgetHeader";
import DashboardWidgetActions from "./DashboardWidgetActions";
import DashboardWidgetContent from "./DashboardWidgetContent";
import DashboardWidgetEmptyState from "./DashboardWidgetEmptyState";
import DashboardWidgetErrorState from "./DashboardWidgetErrorState";
import DashboardWidgetDeleteAction from "./DashboardWidgetDeleteAction";
import DashboardWidgetEditAction from "./DashboardWidgetEditAction";
import DashboardWidgetDuplicateAction from "./DashboardWidgetDuplicateAction";
import DashboardWidgetMoveAction from "./DashboardWidgetMoveAction";
import DashboardWidgetDragHandle from "./DashboardWidgetDragHandle";

type DashboardWidgetComponents = typeof DashboardWidgetRoot & {
  Header: typeof DashboardWidgetHeader;
  Actions: typeof DashboardWidgetActions;
  Content: typeof DashboardWidgetContent;
  EmptyState: typeof DashboardWidgetEmptyState;
  ErrorState: typeof DashboardWidgetErrorState;
  DeleteAction: typeof DashboardWidgetDeleteAction;
  EditAction: typeof DashboardWidgetEditAction;
  DuplicateAction: typeof DashboardWidgetDuplicateAction;
  MoveAction: typeof DashboardWidgetMoveAction;
  DragHandle: typeof DashboardWidgetDragHandle;
};

const DashboardWidget = DashboardWidgetRoot as DashboardWidgetComponents;

DashboardWidget.Header = DashboardWidgetHeader;
DashboardWidget.Actions = DashboardWidgetActions;
DashboardWidget.Content = DashboardWidgetContent;
DashboardWidget.EmptyState = DashboardWidgetEmptyState;
DashboardWidget.ErrorState = DashboardWidgetErrorState;
DashboardWidget.DeleteAction = DashboardWidgetDeleteAction;
DashboardWidget.EditAction = DashboardWidgetEditAction;
DashboardWidget.DuplicateAction = DashboardWidgetDuplicateAction;
DashboardWidget.MoveAction = DashboardWidgetMoveAction;
DashboardWidget.DragHandle = DashboardWidgetDragHandle;

export default DashboardWidget;
