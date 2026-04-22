import React, { useEffect } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";
import useProgressSimulation from "@/hooks/useProgressSimulation";
import OwlArt from "@/shared/OwlArt";

const LOADING_LABELS = [
  "Creating demo project…",
  "Setting up sample traces…",
  "Preparing some data for you…",
  "Almost ready…",
];

const DemoLoadingStep: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const navigate = useNavigate();

  const { data: demoProject, isLoading } = useDemoProject(
    { workspaceName, poll: true },
    { refetchOnMount: "always" },
  );

  const { message } = useProgressSimulation({
    messages: LOADING_LABELS,
    isPending: true,
    loop: true,
  });

  useEffect(() => {
    if (demoProject) {
      void navigate({
        to: "/$workspaceName/projects/$projectId/logs",
        params: { workspaceName, projectId: demoProject.id },
      });
    }
  }, [demoProject, navigate, workspaceName]);

  if (isLoading) {
    return null;
  }

  return (
    <div className="flex min-h-full items-center justify-center">
      <div className="flex flex-col items-center gap-4">
        <OwlArt className="size-[72px]" />
        <p className="font-code text-sm text-muted-slate">
          {message || LOADING_LABELS[0]}
        </p>
      </div>
    </div>
  );
};

export default DemoLoadingStep;
