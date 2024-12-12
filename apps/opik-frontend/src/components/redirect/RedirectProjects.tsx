import React, { useEffect } from "react";
import Loader from "@/components/shared/Loader/Loader";
import { StringParam, useQueryParams } from "use-query-params";
import useAppStore from "@/store/AppStore";
import { Link, Navigate, useNavigate } from "@tanstack/react-router";
import useProjectByName from "@/api/projects/useProjectByName";
import NoData from "@/components/shared/NoData/NoData";
import { Button } from "@/components/ui/button";

const RedirectProjects = () => {
  const [query] = useQueryParams({
    id: StringParam,
    name: StringParam,
  });

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: projectByName, isPending: isPendingProjectByName } =
    useProjectByName(
      { projectName: query.name || "" },
      { enabled: !!query.name && !query.id },
    );

  useEffect(() => {
    if (projectByName?.id) {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: projectByName.id,
          workspaceName,
        },
      });
    }
  }, [projectByName?.id, workspaceName, navigate]);

  if (query.id) {
    return <Navigate to={`/${workspaceName}/projects/${query.id}/traces`} />;
  }

  if (!isPendingProjectByName && !projectByName) {
    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">404</div>}
        title="This project could not be found"
        message="The project you’re looking for doesn’t exist or has been deleted."
      >
        <div className="pt-5">
          <Link to="/$workspaceName/home" params={{ workspaceName }}>
            <Button>Back to Home</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  if (!query.id && !query.name) {
    return <NoData message="No project params set" />;
  }

  return <Loader />;
};

export default RedirectProjects;
