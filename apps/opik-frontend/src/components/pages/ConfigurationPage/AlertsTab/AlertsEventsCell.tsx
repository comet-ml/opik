import { CellContext } from "@tanstack/react-table";

import { Alert, ALERT_EVENT_TYPE } from "@/types/alerts";
import TextCell from "@/components/shared/DataTableCells/TextCell";

const EVENT_TYPE_LABELS: Record<ALERT_EVENT_TYPE, string> = {
  [ALERT_EVENT_TYPE.trace_errors]: "New error",
  [ALERT_EVENT_TYPE.guardrails]: "Guardrail triggered",
  [ALERT_EVENT_TYPE.prompt_creation]: "Prompt creation",
  [ALERT_EVENT_TYPE.prompt_commit]: "Prompt commit",
  [ALERT_EVENT_TYPE.trace_score]: "Trace score",
  [ALERT_EVENT_TYPE.thread_score]: "Thread score",
};

const AlertsEventsCell = (context: CellContext<Alert, unknown>) => {
  const alert = context.row.original;
  const value = !alert.events?.length
    ? "-"
    : alert.events
        .map((event) => EVENT_TYPE_LABELS[event.event_type] || event.event_type)
        .join(", ");

  const textContext = {
    ...context,
    getValue: () => value,
  } as CellContext<Alert, string>;

  return <TextCell {...textContext} />;
};

export default AlertsEventsCell;
