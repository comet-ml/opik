import React, { useEffect } from "react";
import { useParams } from "@tanstack/react-router";
import Loader from "@/shared/Loader/Loader";
import useAlertById from "@/api/alerts/useAlertById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import AlertForm from "./AlertForm";

const AddEditAlertPage: React.FunctionComponent = () => {
  const { alertId } = useParams({ strict: false });
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const isEdit = Boolean(alertId && alertId !== "new");

  const { data: alert, isPending } = useAlertById(
    { alertId: alertId || "" },
    { enabled: isEdit },
  );

  useEffect(() => {
    if (isEdit && alert?.name) {
      setBreadcrumbParam("alertId", alertId!, alert.name);
    }
  }, [isEdit, alertId, alert?.name, setBreadcrumbParam]);

  if (isEdit && isPending) {
    return <Loader />;
  }

  return <AlertForm alert={isEdit ? alert : undefined} />;
};

export default AddEditAlertPage;
