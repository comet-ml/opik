import React, { useState } from "react";
import useWorkflowDisableMutation from "@/api/automations/useWorkflowDisableMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/ui/card";
import { Tag, TagProps } from "@/ui/tag";
import { Workflow, WorkflowState } from "@/types/event-automations";

const STATE: Record<
  WorkflowState,
  { label: string; variant: TagProps["variant"] }
> = {
  off: { label: "Off", variant: "gray" },
  partly_on: { label: "Partly on", variant: "yellow" },
  on: { label: "On", variant: "green" },
};

const summarize = (workflow: Workflow) => {
  // A recipe lists templates for what it would create too; only stored ones can be on.
  const on = workflow.automations.filter((a) => a.id && a.enabled).length;
  const parts = [`${on} of ${workflow.automations.length} automations on`];
  const estimate = workflow.estimate;
  if (estimate && workflow.state !== "on") {
    parts.push(
      `would have written ${estimate.items} items in ${estimate.window_days} days`,
    );
  }
  return parts.join(" · ");
};

type WorkflowsSectionProps = {
  projectId: string;
  workflows: Workflow[];
  isPending: boolean;
  onEnable: (recipe: string) => void;
};

const WorkflowsSection: React.FC<WorkflowsSectionProps> = ({
  projectId,
  workflows,
  isPending,
  onEnable,
}) => {
  const disable = useWorkflowDisableMutation();
  const [disabling, setDisabling] = useState<Workflow | null>(null);

  return (
    <section>
      <h2 className="comet-body-accented">Workflows</h2>
      <p className="comet-body-s mb-3 text-muted-slate">
        The recipes that run the loop. Enabling one creates its automations for
        this project; disabling turns them off and keeps them.
      </p>
      {isPending ? (
        <Loader className="min-h-24" />
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {workflows.map((workflow) => {
            const state = STATE[workflow.state] ?? {
              label: workflow.state,
              variant: "gray" as const,
            };
            const blocked = !workflow.prerequisites_met;
            return (
              <Card key={workflow.recipe} className="flex flex-col">
                <CardHeader className="flex-row items-start justify-between gap-2 space-y-0 p-4 pb-2">
                  <CardTitle className="comet-body-accented">
                    {workflow.name}
                  </CardTitle>
                  <Tag variant={state.variant}>{state.label}</Tag>
                </CardHeader>
                <CardContent className="flex flex-1 flex-col gap-2 p-4 pt-0">
                  {workflow.description && (
                    <p className="comet-body-xs text-muted-slate">
                      {workflow.description}
                    </p>
                  )}
                  {workflow.blocked_reason && (
                    <p className="comet-body-xs text-destructive">
                      {workflow.blocked_reason}
                    </p>
                  )}
                  <p className="comet-body-xs text-muted-slate">
                    {summarize(workflow)}
                  </p>
                  <div className="mt-auto flex gap-2 pt-2">
                    {workflow.state !== "on" && (
                      <TooltipWrapper
                        content={
                          blocked
                            ? workflow.blocked_reason ??
                              "Its prerequisites are not met yet"
                            : undefined
                        }
                      >
                        <span>
                          <Button
                            size="xs"
                            disabled={blocked}
                            onClick={() => onEnable(workflow.recipe)}
                          >
                            {workflow.state === "partly_on"
                              ? "Enable the rest"
                              : "Enable"}
                          </Button>
                        </span>
                      </TooltipWrapper>
                    )}
                    {workflow.state !== "off" && (
                      <Button
                        size="xs"
                        variant="outline"
                        disabled={disable.isPending}
                        onClick={() => setDisabling(workflow)}
                      >
                        Disable
                      </Button>
                    )}
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
      {disabling && (
        <ConfirmDialog
          open
          setOpen={(open) => !open && setDisabling(null)}
          title={`Disable ${disabling.name}?`}
          description={`Turns off the ${
            disabling.automations.length
          } automation${disabling.automations.length === 1 ? "" : "s"} ${
            disabling.name
          } created for this project. Nothing is deleted; enabling again reuses them.`}
          confirmText="Disable"
          confirmButtonVariant="destructive"
          onConfirm={() =>
            disable.mutate({ recipe: disabling.recipe, projectId })
          }
        />
      )}
    </section>
  );
};

export default WorkflowsSection;
