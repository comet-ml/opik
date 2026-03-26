import React, { useEffect } from "react";
import last from "lodash/last";
import { Link, Navigate, Outlet, useLocation } from "@tanstack/react-router";
import useProjectById from "@/api/projects/useProjectById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useActiveProjectId, useActiveWorkspaceName } from "@/store/AppStore";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { setActiveProject } from "@/hooks/useActiveProjectInitializer";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import { Button } from "@/ui/button";

const ProjectPage = () => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const projectId = useProjectIdFromURL();
  const workspaceName = useActiveWorkspaceName();

  const activeProjectId = useActiveProjectId();

  useEffect(() => {
    setActiveProject(workspaceName, projectId);
  }, [projectId, workspaceName]);

  const { data, isPending, isError } = useProjectById({
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

  if (isPending || activeProjectId !== projectId) {
    return <Loader />;
  }

  if (isError || !data) {
    setActiveProject(workspaceName, null);
    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">404</div>}
        title="This project could not be found"
        message="The project you're looking for doesn't exist or has been deleted."
      >
        <div className="pt-5">
          <Link to="/$workspaceName/projects" params={{ workspaceName }}>
            <Button>Back to Projects</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  if (last(pathname.split("/")) === projectId) {
    return <Navigate to={pathname + "/home"} />;
  }

  return <Outlet />;
};

export default ProjectPage;
