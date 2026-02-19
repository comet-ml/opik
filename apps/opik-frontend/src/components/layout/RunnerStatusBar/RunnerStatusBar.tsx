import React, { useState } from "react";
import { Plug, PlugZap } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import useMyRunner, {
  getStoredRunnerId,
  clearStoredRunnerId,
} from "@/api/runners/useMyRunner";
import PairingDialog from "@/components/pages/TracesPage/ExecutionTab/PairingDialog";

const RunnerStatusBar: React.FunctionComponent = () => {
  const [pairingOpen, setPairingOpen] = useState(false);
  const [detailsOpen, setDetailsOpen] = useState(false);

  const { data: runner } = useMyRunner({
    refetchInterval: 5000,
  });

  const runnerId = getStoredRunnerId();
  const isConnected = runner?.status === "connected";

  const agentCount = runner?.agents?.length ?? 0;

  const handleClick = () => {
    if (isConnected) {
      setDetailsOpen(true);
    } else {
      setPairingOpen(true);
    }
  };

  const handleDisconnect = () => {
    clearStoredRunnerId();
    setDetailsOpen(false);
  };

  return (
    <>
      <button
        onClick={handleClick}
        className="comet-content-inset absolute bottom-0 right-0 flex h-7 items-center gap-2 border-t bg-background px-4 text-xs transition-all hover:bg-muted/50"
      >
        {isConnected ? (
          <>
            <span className="size-2 rounded-full bg-green-500" />
            <PlugZap className="size-3 text-green-600" />
            <span className="text-foreground">
              Connected — {runner.name}
            </span>
            {agentCount > 0 && (
              <span className="text-muted-slate">
                · {agentCount} agent{agentCount !== 1 ? "s" : ""}
              </span>
            )}
          </>
        ) : (
          <>
            <span className="size-2 rounded-full bg-muted-slate/40" />
            <Plug className="size-3 text-muted-slate" />
            <span className="text-muted-slate">
              {runnerId ? "Runner disconnected" : "No runner connected"}
            </span>
          </>
        )}
      </button>

      <PairingDialog open={pairingOpen} setOpen={setPairingOpen} />

      <Dialog open={detailsOpen} onOpenChange={setDetailsOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Runner connected</DialogTitle>
          </DialogHeader>

          <div className="space-y-4">
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-slate">Name</span>
                <span>{runner?.name}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-slate">Status</span>
                <span className="flex items-center gap-1.5">
                  <span className="size-2 rounded-full bg-green-500" />
                  Connected
                </span>
              </div>
              {runner?.connected_at && (
                <div className="flex justify-between">
                  <span className="text-muted-slate">Connected since</span>
                  <span>
                    {new Date(runner.connected_at).toLocaleTimeString()}
                  </span>
                </div>
              )}
            </div>

            {agentCount > 0 && (
              <div>
                <div className="mb-2 text-sm font-medium">
                  Registered agents
                </div>
                <div className="space-y-1">
                  {runner?.agents?.map((agent) => (
                    <div
                      key={agent.name}
                      className="flex items-center justify-between rounded-md bg-muted px-3 py-1.5 text-xs"
                    >
                      <span className="font-mono">{agent.name}</span>
                      <span className="text-muted-slate">
                        {agent.project}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="flex justify-end">
              <Button
                variant="outline"
                size="sm"
                onClick={handleDisconnect}
              >
                Disconnect
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
};

export default RunnerStatusBar;
