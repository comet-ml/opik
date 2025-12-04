import React from "react";
import { Button } from "@/components/ui/button";
import { ChevronsRight } from "lucide-react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type IntegrationSkipProps = {
  className?: string;
  label?: string;
};

const IntegrationSkip: React.FunctionComponent<IntegrationSkipProps> = ({
  className,
  label = "Skip & explore platform",
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <Button
      variant="outline"
      size="sm"
      asChild
      className={className}
      id="quickstart-skip-explore-platform"
      data-fs-element="QuickstartSkipExplorePlatform"
    >
      <Link to="/$workspaceName/home" params={{ workspaceName }}>
        {label}
        <ChevronsRight className="ml-1.5 size-3.5" />
      </Link>
    </Button>
  );
};

export default IntegrationSkip;
