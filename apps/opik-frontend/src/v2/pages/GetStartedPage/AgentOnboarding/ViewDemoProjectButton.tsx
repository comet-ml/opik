import React from "react";
import { ArrowUpRight } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";

const ViewDemoProjectButton: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const { data: demoProject } = useDemoProject({ workspaceName });

  if (!demoProject) {
    return null;
  }

  return (
    <Button
      variant="link"
      className="comet-body-s px-0 text-foreground"
      asChild
      id="onboarding-step2-view-demo"
      data-fs-element="onboarding-step2-view-demo"
    >
      <Link
        to="/$workspaceName/projects/$projectId/logs"
        params={{
          workspaceName,
          projectId: demoProject.id,
        }}
      >
        View Demo project
        <ArrowUpRight className="ml-1 size-3.5" />
      </Link>
    </Button>
  );
};

export default ViewDemoProjectButton;
