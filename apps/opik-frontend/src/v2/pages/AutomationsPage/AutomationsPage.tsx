import React, { useState } from "react";
import useWorkflowsList from "@/api/automations/useWorkflowsList";
import { useActiveProjectId } from "@/store/AppStore";
import { WorkflowOverrides } from "@/types/event-automations";
import AutomationsTable from "./AutomationsTable";
import LoopSection from "./LoopSection";
import RecommendationsSection from "./RecommendationsSection";
import WorkflowEnableDialog from "./WorkflowEnableDialog";
import WorkflowsSection from "./WorkflowsSection";

type EnableTarget = {
  recipe: string;
  suggested?: WorkflowOverrides;
};

const AutomationsPage: React.FC = () => {
  const projectId = useActiveProjectId()!;
  const { data: workflows = [], isPending } = useWorkflowsList({ projectId });
  const [enableTarget, setEnableTarget] = useState<EnableTarget | null>(null);

  return (
    <div className="flex min-h-full flex-col gap-8 pb-6 pt-4">
      <div>
        <h1 className="comet-body-accented">Automations</h1>
        <p className="comet-body-s text-muted-slate">
          What runs on this project&apos;s events and what it produced.
          Automations come from the workflow recipes or the Opik MCP.
        </p>
      </div>
      <LoopSection projectId={projectId} />
      <RecommendationsSection
        projectId={projectId}
        onEnable={(recipe, suggested) => setEnableTarget({ recipe, suggested })}
      />
      <WorkflowsSection
        projectId={projectId}
        workflows={workflows}
        isPending={isPending}
        onEnable={(recipe) => setEnableTarget({ recipe })}
      />
      <AutomationsTable projectId={projectId} />
      {enableTarget && (
        <WorkflowEnableDialog
          open
          setOpen={(open) => !open && setEnableTarget(null)}
          projectId={projectId}
          recipe={enableTarget.recipe}
          workflow={workflows.find((w) => w.recipe === enableTarget.recipe)}
          suggested={enableTarget.suggested}
        />
      )}
    </div>
  );
};

export default AutomationsPage;
