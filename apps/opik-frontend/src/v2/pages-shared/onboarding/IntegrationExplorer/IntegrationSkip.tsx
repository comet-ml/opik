import React from "react";
import { Button } from "@/ui/button";
import { ChevronsRight } from "lucide-react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";

type IntegrationSkipProps = {
  className?: string;
  label?: string;
  onSkip?: () => void;
};

const IntegrationSkip: React.FunctionComponent<IntegrationSkipProps> = ({
  className,
  label = "Skip & explore platform",
  onSkip,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: demoProject, isLoading } = useDemoProject({ workspaceName });

  const content = (
    <>
      {label}
      <ChevronsRight className="ml-1.5 size-3.5" />
    </>
  );

  const buttonProps = {
    variant: "outline" as const,
    size: "sm" as const,
    className,
    id: "quickstart-skip-explore-platform",
    "data-fs-element": "QuickstartSkipExplorePlatform",
  };

  if (onSkip && !demoProject && !isLoading) {
    return (
      <Button {...buttonProps} onClick={onSkip}>
        {content}
      </Button>
    );
  }

  if (demoProject) {
    return (
      <Button {...buttonProps} asChild>
        <Link
          to="/$workspaceName/projects/$projectId/logs"
          params={{ workspaceName, projectId: demoProject.id }}
        >
          {content}
        </Link>
      </Button>
    );
  }

  return (
    <Button {...buttonProps} asChild>
      <Link to="/$workspaceName/home" params={{ workspaceName }}>
        {content}
      </Link>
    </Button>
  );
};

export default IntegrationSkip;
