import { CellContext } from "@tanstack/react-table";

import { Alert, ALERT_EVENT_TYPE } from "@/types/alerts";
import TextCell from "@/components/shared/DataTableCells/TextCell";

const EVENT_TYPE_LABELS: Record<ALERT_EVENT_TYPE, string> = {
  [ALERT_EVENT_TYPE.trace_errors]: "New error in trace",
  [ALERT_EVENT_TYPE.trace_guardrails_triggered]: "Guardrail triggered",
  [ALERT_EVENT_TYPE.prompt_created]: "Prompt created",
  [ALERT_EVENT_TYPE.prompt_committed]: "Prompt committed",
  [ALERT_EVENT_TYPE.prompt_deleted]: "Prompt deleted",
  [ALERT_EVENT_TYPE.trace_feedback_score]: "Trace score",
  [ALERT_EVENT_TYPE.trace_thread_feedback_score]: "Thread score",
};

const AlertsEventsCell = (context: CellContext<Alert, unknown>) => {
  const alert = context.row.original;
  const value = !alert.triggers?.length
    ? "-"
    : alert.triggers
        .map(
          (trigger) =>
            EVENT_TYPE_LABELS[trigger.event_type] || trigger.event_type,
        )
        .join(", ");

  const textContext = {
    ...context,
    getValue: () => value,
  } as CellContext<Alert, string>;

  return <TextCell {...textContext} />;
};

export default AlertsEventsCell;
