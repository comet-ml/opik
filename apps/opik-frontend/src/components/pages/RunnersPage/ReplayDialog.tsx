import React, { useCallback, useState } from "react";

import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import useCreateRunnerJobMutation from "@/api/runners/useCreateRunnerJobMutation";
import { Runner } from "@/types/runners";

type ReplayDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  runners: Runner[];
  traceInput: object;
  projectName: string;
};

const ReplayDialog: React.FunctionComponent<ReplayDialogProps> = ({
  open,
  setOpen,
  runners,
  traceInput,
  projectName,
}) => {
  const { toast } = useToast();
  const createJobMutation = useCreateRunnerJobMutation();
  const [selectedAgent, setSelectedAgent] = useState<string>("");

  const allAgents = runners.flatMap(
    (r) => r.agents?.map((a) => ({ ...a, runnerId: r.id, runnerName: r.name })) ?? [],
  );

  const handleReplay = useCallback(() => {
    const agent = allAgents.find((a) => a.name === selectedAgent);
    if (!agent) return;

    createJobMutation.mutate(
      {
        agent_name: agent.name,
        inputs: traceInput,
        project: agent.project || projectName,
      },
      {
        onSuccess: () => {
          toast({
            title: "Job created",
            description: `Replay job sent to agent "${agent.name}"`,
          });
          setOpen(false);
        },
      },
    );
  }, [
    allAgents,
    selectedAgent,
    createJobMutation,
    traceInput,
    projectName,
    toast,
    setOpen,
  ]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Replay Trace</DialogTitle>
        </DialogHeader>

        <div className="space-y-3">
          <div className="comet-body-s">
            Select an agent to replay this trace input:
          </div>

          {allAgents.length === 0 ? (
            <div className="comet-body-s text-muted-slate">
              No agents available. Connect a runner first.
            </div>
          ) : (
            <div className="space-y-1">
              {allAgents.map((agent) => (
                <button
                  key={`${agent.runnerId}-${agent.name}`}
                  className={`flex w-full items-center justify-between rounded-md border px-3 py-2 text-sm ${
                    selectedAgent === agent.name
                      ? "border-primary bg-primary/5"
                      : "hover:bg-muted/50"
                  }`}
                  onClick={() => setSelectedAgent(agent.name)}
                >
                  <span className="font-mono">{agent.name}</span>
                  <span className="text-muted-slate text-xs">
                    on {agent.runnerName}
                  </span>
                </button>
              ))}
            </div>
          )}

          <div className="comet-body-xs text-muted-slate">
            Input preview:{" "}
            <span className="font-mono">
              {JSON.stringify(traceInput).slice(0, 100)}
              {JSON.stringify(traceInput).length > 100 ? "..." : ""}
            </span>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            onClick={handleReplay}
            disabled={!selectedAgent || createJobMutation.isPending}
          >
            {createJobMutation.isPending ? "Sending..." : "Replay"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ReplayDialog;
