import { CellContext } from "@tanstack/react-table";

import { Alert } from "@/types/alerts";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import { TRIGGER_CONFIG } from "@/components/pages/AlertsPage/AddEditAlertPage/helpers";

const AlertsEventsCell = (context: CellContext<Alert, unknown>) => {
  const alert = context.row.original;
  const value = !alert.triggers?.length
    ? "-"
    : alert.triggers
        .map(
          (trigger) =>
            TRIGGER_CONFIG[trigger.event_type]?.title || trigger.event_type,
        )
        .join(", ");

  const textContext = {
    ...context,
    getValue: () => value,
  } as CellContext<Alert, string>;

  return <TextCell {...textContext} />;
};

export default AlertsEventsCell;
