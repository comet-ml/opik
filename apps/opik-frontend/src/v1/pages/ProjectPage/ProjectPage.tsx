import React, { useEffect } from "react";
import last from "lodash/last";
import { Navigate, Outlet, useLocation } from "@tanstack/react-router";
import useProjectById from "@/api/projects/useProjectById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";

const ProjectPage = () => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const projectId = useProjectIdFromURL();

  const { data } = useProjectById({
    projectId,
  });

  useEffect(() => {
    if (data?.name) {
      setBreadcrumbParam("projectId", projectId, data.name);
    }
  }, [projectId, data?.name, setBreadcrumbParam]);

  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  if (last(pathname.split("/")) === projectId) {
    return <Navigate to={pathname + "/traces"} />;
  }

  return <Outlet />;
};

export default ProjectPage;
