import React from "react";
import { Copy, Play, Terminal, ArrowLeft } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { Separator } from "@/components/ui/separator";
import { useToast } from "@/components/ui/use-toast";

type ExperimentCreatedPanelProps = {
  experimentId: string;
  onBack: () => void;
};

const ExperimentCreatedPanel: React.FC<ExperimentCreatedPanelProps> = ({
  experimentId,
  onBack,
}) => {
  const { toast } = useToast();

  const handleCopyHeader = () => {
    navigator.clipboard.writeText(`X-Opik-Experiment-Id: ${experimentId}`);
    toast({ description: "Header copied to clipboard" });
  };

  const handleCopyId = () => {
    navigator.clipboard.writeText(experimentId);
    toast({ description: "Experiment ID copied to clipboard" });
  };

  return (
    <div className="flex h-full flex-col">
      <div className="border-b p-4">
        <Button
          variant="ghost"
          size="sm"
          onClick={onBack}
          className="mb-2 -ml-2"
        >
          <ArrowLeft className="mr-1.5 size-4" />
          Back to Configuration
        </Button>
        <h2 className="comet-title-l">Experiment Created</h2>
      </div>

      <div className="flex-1 overflow-auto p-6">
        <div className="space-y-6">
          <div className="rounded-lg border bg-muted/30 p-4">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium">Experiment ID</span>
              <Button variant="ghost" size="icon-sm" onClick={handleCopyId}>
                <Copy className="size-4" />
              </Button>
            </div>
            <code className="block break-all rounded bg-background p-2 font-mono text-sm">
              {experimentId}
            </code>
          </div>

          <Separator />

          <div className="space-y-4">
            <div className="flex items-center gap-2">
              <Play className="size-5 text-muted-slate" />
              <h3 className="font-medium">Run Agent</h3>
              <Tag variant="gray" size="sm">
                Coming Soon
              </Tag>
            </div>
            <p className="text-sm text-muted-slate">
              Trigger your agent directly from the UI with this experiment
              configuration.
            </p>
            <Button disabled className="w-full">
              <Play className="mr-1.5 size-4" />
              Run Agent (Coming Soon)
            </Button>
          </div>

          <Separator />

          <div className="space-y-4">
            <div className="flex items-center gap-2">
              <Terminal className="size-5 text-muted-slate" />
              <h3 className="font-medium">Run it Yourself</h3>
            </div>
            <p className="text-sm text-muted-slate">
              To use this experiment configuration, include the following header
              in your HTTP request to your agent:
            </p>
            <div className="rounded-lg border bg-muted/30 p-4">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-medium uppercase text-muted-slate">
                  HTTP Header
                </span>
                <Button variant="ghost" size="icon-sm" onClick={handleCopyHeader}>
                  <Copy className="size-4" />
                </Button>
              </div>
              <code className="block break-all rounded bg-background p-3 font-mono text-sm">
                X-Opik-Experiment-Id: {experimentId}
              </code>
            </div>

            <div className="rounded-lg border p-4">
              <p className="mb-2 text-sm font-medium">Example with curl:</p>
              <pre className="overflow-x-auto rounded bg-muted/30 p-3 text-xs">
{`curl -X POST http://your-agent/run \\
  -H "Content-Type: application/json" \\
  -H "X-Opik-Experiment-Id: ${experimentId}" \\
  -d '{"input": "Hello, agent!"}'`}
              </pre>
            </div>

            <div className="rounded-lg border p-4">
              <p className="mb-2 text-sm font-medium">Example with Python:</p>
              <pre className="overflow-x-auto rounded bg-muted/30 p-3 text-xs">
{`import requests

response = requests.post(
    "http://your-agent/run",
    headers={
        "X-Opik-Experiment-Id": "${experimentId}"
    },
    json={"input": "Hello, agent!"}
)`}
              </pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ExperimentCreatedPanel;
