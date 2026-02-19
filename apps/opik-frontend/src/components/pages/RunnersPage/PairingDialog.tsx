import React, { useCallback, useEffect, useState } from "react";
import copy from "clipboard-copy";
import { Copy, Loader2 } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import useRunnerPairMutation from "@/api/runners/useRunnerPairMutation";
import useRunnerById from "@/api/runners/useRunnerById";

type PairingDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const PairingDialog: React.FunctionComponent<PairingDialogProps> = ({
  open,
  setOpen,
}) => {
  const { toast } = useToast();
  const [pairingCode, setPairingCode] = useState<string | null>(null);
  const [runnerId, setRunnerId] = useState<string | null>(null);
  const [expiresIn, setExpiresIn] = useState<number>(0);
  const pairMutation = useRunnerPairMutation();

  const { data: runner } = useRunnerById(
    { runnerId: runnerId ?? "" },
    {
      refetchInterval: runnerId ? 2000 : false,
      enabled: open && !!runnerId,
    },
  );

  useEffect(() => {
    if (runner && runner.status === "connected") {
      toast({ description: `Runner '${runner.name}' connected` });
      setOpen(false);
    }
  }, [runner, toast, setOpen]);

  const handleGenerate = useCallback(() => {
    pairMutation.mutate(undefined, {
      onSuccess: (data) => {
        setPairingCode(data.pairing_code);
        setRunnerId(data.runner_id);
        setExpiresIn(data.expires_in_seconds);
      },
    });
  }, [pairMutation]);

  useEffect(() => {
    if (open && !pairingCode) {
      handleGenerate();
    }
  }, [open]);

  useEffect(() => {
    if (!open) {
      setPairingCode(null);
      setRunnerId(null);
      setExpiresIn(0);
    }
  }, [open]);

  useEffect(() => {
    if (expiresIn <= 0) return;

    const timer = setInterval(() => {
      setExpiresIn((prev) => {
        if (prev <= 1) {
          clearInterval(timer);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, [expiresIn]);

  const cliCommand = pairingCode
    ? `opik connect --pair ${pairingCode}`
    : "";

  const handleCopy = useCallback(() => {
    copy(cliCommand);
    toast({ description: "Command copied to clipboard" });
  }, [cliCommand, toast]);

  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, "0")}`;
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Connect a Runner</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          {pairingCode ? (
            <>
              <div className="text-center">
                <div className="comet-body-s mb-2 text-muted-slate">
                  Pairing Code
                </div>
                <div className="font-mono text-3xl font-bold tracking-widest">
                  {pairingCode}
                </div>
                {expiresIn > 0 && (
                  <div className="comet-body-xs mt-1 text-muted-slate">
                    Expires in {formatTime(expiresIn)}
                  </div>
                )}
                {expiresIn === 0 && (
                  <div className="comet-body-xs mt-1 text-destructive">
                    Code expired.{" "}
                    <button
                      className="underline"
                      onClick={handleGenerate}
                    >
                      Generate new code
                    </button>
                  </div>
                )}
              </div>

              <div className="space-y-2">
                <div className="comet-body-s text-muted-slate">
                  Run this in your project directory:
                </div>
                <div className="flex items-center gap-2 rounded-md bg-muted p-3">
                  <code className="flex-1 font-mono text-sm">
                    {cliCommand}
                  </code>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={handleCopy}
                  >
                    <Copy className="size-4" />
                  </Button>
                </div>
              </div>

              <div className="comet-body-xs text-center text-muted-slate">
                Waiting for runner to connect...
              </div>
            </>
          ) : (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="size-6 animate-spin text-muted-slate" />
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default PairingDialog;
