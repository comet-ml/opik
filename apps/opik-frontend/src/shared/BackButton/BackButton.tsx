import React from "react";
import { ArrowLeft } from "lucide-react";
import { Link, LinkProps } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

export type BackButtonProps = {
  to: LinkProps["to"];
  params?: Record<string, string>;
  search?: LinkProps["search"];
  tooltip: string;
  className?: string;
  iconClassName?: string;
};

const BackButton: React.FunctionComponent<BackButtonProps> = ({
  to,
  params,
  search,
  tooltip,
  className,
  iconClassName,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const linkParams: Record<string, string> = {
    workspaceName,
    ...(activeProjectId ? { projectId: activeProjectId } : {}),
    ...params,
  };

  return (
    <TooltipWrapper content={tooltip}>
      <Link
        to={to}
        params={linkParams}
        search={search}
        className={cn(
          "flex size-6 shrink-0 items-center justify-center rounded-md text-foreground hover:bg-primary-foreground",
          className,
        )}
      >
        <ArrowLeft className={cn("size-4", iconClassName)} />
      </Link>
    </TooltipWrapper>
  );
};

export default BackButton;
