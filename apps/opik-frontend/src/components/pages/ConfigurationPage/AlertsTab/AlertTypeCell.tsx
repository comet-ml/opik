import React from "react";
import { CellContext } from "@tanstack/react-table";

import { Alert, ALERT_TYPE } from "@/types/alerts";
import {
  ALERT_TYPE_ICONS,
  ALERT_TYPE_LABELS,
} from "./AddEditAlertPage/helpers";

const AlertTypeCell: React.FunctionComponent<CellContext<Alert, unknown>> = (
  context,
) => {
  const alert = context.row.original;
  const type = alert.alert_type || ALERT_TYPE.general;
  const Icon = ALERT_TYPE_ICONS[type];
  const label = ALERT_TYPE_LABELS[type];

  return (
    <div className="flex items-center gap-2">
      <Icon className="size-3 shrink-0" />
      <span>{label}</span>
    </div>
  );
};

export default AlertTypeCell;
