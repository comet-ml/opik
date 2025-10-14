import React, { useEffect, useMemo } from "react";
import { useParams } from "@tanstack/react-router";
import Loader from "@/components/shared/Loader/Loader";
import useAlertById from "@/api/alerts/useAlertById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import AlertForm from "./AlertForm";
import { useProjectsSelectData } from "@/components/pages-shared/automations/ProjectsSelectBox";

const AddEditAlertPage: React.FunctionComponent = () => {
  const { alertId } = useParams({ strict: false });
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const isEdit = Boolean(alertId && alertId !== "new");

  const { data: alert, isPending } = useAlertById(
    { alertId: alertId || "" },
    { enabled: isEdit },
  );

  const { projects, isLoading } = useProjectsSelectData({});

  useEffect(() => {
    if (isEdit && alert?.name) {
      setBreadcrumbParam("alertId", alertId!, alert.name);
    }
  }, [isEdit, alertId, alert?.name, setBreadcrumbParam]);

  const projectIds = useMemo(() => projects.map((p) => p.id), [projects]);

  if ((isEdit && isPending) || isLoading) {
    return <Loader />;
  }

  return (
    <AlertForm alert={isEdit ? alert : undefined} projectsIds={projectIds} />
  );
};

export default AddEditAlertPage;
