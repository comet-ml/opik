import React from "react";
import { CellContext } from "@tanstack/react-table";

import { Alert, ALERT_TYPE } from "@/types/alerts";
import {
  ALERT_TYPE_ICONS,
  ALERT_TYPE_LABELS,
} from "./AddEditAlertPage/helpers";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const AlertTypeCell: React.FunctionComponent<CellContext<Alert, unknown>> = (
  context,
) => {
  const alert = context.row.original;
  const type = alert.alert_type || ALERT_TYPE.general;
  const Icon = ALERT_TYPE_ICONS[type];
  const label = ALERT_TYPE_LABELS[type];

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="flex items-center gap-1">
        <Icon className="size-3 shrink-0 text-muted-slate" />
        <span>{label}</span>
      </div>
    </CellWrapper>
  );
};

export default AlertTypeCell;
