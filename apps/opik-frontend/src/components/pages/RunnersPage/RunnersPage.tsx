import React, { useState } from "react";
import { Cpu, Plus } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useRunnersList from "@/api/runners/useRunnersList";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import PairingDialog from "./PairingDialog";
import RunnerDetail from "./RunnerDetail";

const RunnersPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [pairingOpen, setPairingOpen] = useState(false);

  const { data: runners, isPending } = useRunnersList(
    { workspaceName },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 5000,
    },
  );

  if (isPending) {
    return <Loader />;
  }

  const hasRunners = runners && runners.length > 0;

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">Runners</h1>
        <Button size="sm" onClick={() => setPairingOpen(true)}>
          <Plus className="mr-1.5 size-3.5" />
          Connect a Runner
        </Button>
      </div>

      {hasRunners ? (
        <div className="space-y-3">
          {runners.map((runner) => (
            <RunnerDetail key={runner.id} runner={runner} />
          ))}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center rounded-lg border border-dashed py-16">
          <Cpu className="mb-4 size-12 text-muted-slate" />
          <div className="mb-2 text-lg font-medium">No connected agents</div>
          <div className="comet-body-s mb-4 max-w-sm text-center text-muted-slate">
            Connect a local runner to execute agent functions. Mark your
            functions with <code className="font-mono">@entrypoint</code> and
            run <code className="font-mono">opik connect</code>.
          </div>
          <Button onClick={() => setPairingOpen(true)}>
            <Plus className="mr-1.5 size-3.5" />
            Connect a Runner
          </Button>
        </div>
      )}

      <PairingDialog open={pairingOpen} setOpen={setPairingOpen} />
    </div>
  );
};

export default RunnersPage;
