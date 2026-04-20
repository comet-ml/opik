import React from "react";
import { ArrowRight, Loader2 } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";

const ShowDemoProjectButton: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const { data: demoProject } = useDemoProject({ workspaceName, poll: true });

  if (!demoProject) {
    return (
      <Button
        disabled
        id="onboarding-step2-show-demo"
        data-fs-element="onboarding-step2-show-demo"
      >
        <Loader2 className="mr-2 size-4 animate-spin" />
        Generating demo data…
      </Button>
    );
  }

  return (
    <Button
      asChild
      id="onboarding-step2-show-demo"
      data-fs-element="onboarding-step2-show-demo"
    >
      <Link
        to="/$workspaceName/projects/$projectId/logs"
        params={{
          workspaceName,
          projectId: demoProject.id,
        }}
      >
        Show me a demo project
        <ArrowRight className="ml-1 size-3.5" />
      </Link>
    </Button>
  );
};

export default ShowDemoProjectButton;
