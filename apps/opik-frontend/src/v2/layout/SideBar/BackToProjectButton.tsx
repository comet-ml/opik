import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { Undo2 } from "lucide-react";

import { useActiveProjectId, useActiveWorkspaceName } from "@/store/AppStore";
import useProjectById from "@/api/projects/useProjectById";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface BackToProjectButtonProps {
  expanded: boolean;
}

const BackToProjectButton: React.FC<BackToProjectButtonProps> = ({
  expanded,
}) => {
  const navigate = useNavigate();
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();

  const { data: activeProject } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const hasActiveProject = !!activeProjectId;
  const label = activeProject
    ? `Back to ${activeProject.name}`
    : "Back to project";
  // When disabled, explain why via tooltip so the affordance isn't a dead-end.
  // In collapsed mode we always want a tooltip (to surface the hidden label).
  const tooltipContent = !hasActiveProject
    ? "Select a project on the Projects page"
    : !expanded
      ? label
      : null;

  const handleClick = () => {
    if (!activeProjectId) return;
    navigate({
      to: "/$workspaceName/projects/$projectId/home",
      params: { workspaceName, projectId: activeProjectId },
    });
  };

  const button = expanded ? (
    <Button
      variant="outline"
      size="2xs"
      className="comet-body-xs-accented w-fit max-w-full gap-1 rounded px-1.5"
      onClick={handleClick}
      disabled={!hasActiveProject}
    >
      <Undo2 className="size-3 shrink-0" />
      <span className="truncate">{label}</span>
    </Button>
  ) : (
    <Button
      variant="outline"
      size="icon-2xs"
      className="rounded"
      onClick={handleClick}
      disabled={!hasActiveProject}
    >
      <Undo2 />
    </Button>
  );

  if (!tooltipContent) {
    return button;
  }

  return (
    <TooltipWrapper content={tooltipContent} side="right" delayDuration={0}>
      {/* span wrapper lets the tooltip receive hover events even when the
          button inside is disabled */}
      <span className="inline-block max-w-full">{button}</span>
    </TooltipWrapper>
  );
};

export default BackToProjectButton;
