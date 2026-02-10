import React, { useState } from "react";
import { FlaskConical, Copy, Terminal, ArrowLeft } from "lucide-react";

import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tag } from "@/components/ui/tag";
import { Separator } from "@/components/ui/separator";
import { ConfigVariable } from "@/api/config/useConfigVariables";
import useCreateExperiment from "@/api/config/useCreateExperiment";
import { useToast } from "@/components/ui/use-toast";

type ConfigurationSidePanelProps = {
  variable: ConfigVariable | null;
  mode: "update" | "experiment" | null;
  open: boolean;
  onClose: () => void;
  projectId: string;
  onSave?: (key: string, value: string | number | boolean) => void;
  isSaving?: boolean;
};

const ConfigurationSidePanel: React.FC<ConfigurationSidePanelProps> = ({
  variable,
  mode,
  open,
  onClose,
  onSave,
  isSaving,
}) => {
  const [editValue, setEditValue] = useState<string>("");
  const [experimentValue, setExperimentValue] = useState<string>("");
  const [createdExperimentId, setCreatedExperimentId] = useState<string | null>(
    null,
  );

  const createMutation = useCreateExperiment();
  const { toast } = useToast();

  React.useEffect(() => {
    if (variable) {
      setEditValue(String(variable.currentValue));
      setExperimentValue(String(variable.currentValue));
      setCreatedExperimentId(null);
    }
  }, [variable]);

  if (!variable) return null;

  const handleSave = () => {
    if (!onSave) return;

    let value: string | number | boolean = editValue;
    if (variable.type === "boolean") {
      value = editValue === "true";
    } else if (variable.type === "number") {
      value = parseFloat(editValue);
    }

    onSave(variable.key, value);
  };

  const isPromptType = variable.type === "prompt";
  const hasChanges = String(variable.currentValue) !== editValue;

  const renderValueEditor = () => {
    if (variable.type === "boolean") {
      return (
        <div className="flex gap-2">
          <Button
            variant={editValue === "true" ? "default" : "outline"}
            size="sm"
            onClick={() => setEditValue("true")}
          >
            True
          </Button>
          <Button
            variant={editValue === "false" ? "default" : "outline"}
            size="sm"
            onClick={() => setEditValue("false")}
          >
            False
          </Button>
        </div>
      );
    }

    if (variable.type === "number") {
      return (
        <Input
          type="number"
          value={editValue}
          onChange={(e) => setEditValue(e.target.value)}
          step={variable.key === "temperature" ? 0.1 : 1}
        />
      );
    }

    return (
      <Input value={editValue} onChange={(e) => setEditValue(e.target.value)} />
    );
  };

  const renderUpdateContent = () => (
    <div className="flex h-full flex-col">
      <div className="flex-1 overflow-auto p-6">
        <div className="space-y-6">
          <div>
            <h2 className="comet-title-l mb-1">{variable.key}</h2>
            <div className="flex items-center gap-2">
              <Tag variant="default" size="sm" className="capitalize">
                {variable.type}
              </Tag>
              {variable.version > 1 && (
                <Tag variant="gray" size="sm">
                  v{variable.version}
                </Tag>
              )}
            </div>
          </div>

          {variable.annotations && variable.annotations.length > 0 && (
            <div>
              <h3 className="mb-2 text-sm font-medium">Context</h3>
              <ul className="list-inside list-disc space-y-1 text-sm text-muted-slate">
                {variable.annotations.map((annotation, idx) => (
                  <li key={idx}>{annotation}</li>
                ))}
              </ul>
            </div>
          )}

          <Separator />

          {isPromptType ? (
            <div className="rounded-md border bg-muted/30 p-4">
              <p className="text-sm text-muted-slate">
                Prompt editing not yet supported in this view.
              </p>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="space-y-2">
                <Label>Current Value</Label>
                {renderValueEditor()}
              </div>
              <div className="space-y-2">
                <Label className="text-muted-slate">Fallback</Label>
                <div className="rounded-md border bg-muted/30 px-3 py-2 font-mono text-sm">
                  {String(variable.fallback)}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {!isPromptType && (
        <div className="border-t bg-background p-4">
          <Button
            className="w-full"
            onClick={handleSave}
            disabled={!hasChanges || isSaving}
          >
            {isSaving ? "Saving..." : "Save Changes"}
          </Button>
        </div>
      )}
    </div>
  );

  const handleCreateExperiment = () => {
    let value: string | number | boolean = experimentValue;
    if (variable.type === "boolean") {
      value = experimentValue === "true";
    } else if (variable.type === "number") {
      value = parseFloat(experimentValue);
    }

    createMutation.mutate(
      { variables: [{ key: variable.key, value }] },
      {
        onSuccess: (result) => {
          setCreatedExperimentId(result.experimentId);
        },
      },
    );
  };

  const handleCopyHeader = () => {
    if (createdExperimentId) {
      navigator.clipboard.writeText(
        `X-Opik-Experiment-Id: ${createdExperimentId}`,
      );
      toast({ description: "Header copied to clipboard" });
    }
  };

  const handleCopyId = () => {
    if (createdExperimentId) {
      navigator.clipboard.writeText(createdExperimentId);
      toast({ description: "Experiment ID copied to clipboard" });
    }
  };

  const renderExperimentInstructions = () => (
    <div className="flex h-full flex-col">
      <div className="border-b p-4">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => setCreatedExperimentId(null)}
          className="mb-2 -ml-2"
        >
          <ArrowLeft className="mr-1.5 size-4" />
          Back
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
              {createdExperimentId}
            </code>
          </div>

          <div className="rounded-lg border bg-muted/30 p-4">
            <p className="mb-2 text-sm font-medium">Override</p>
            <div className="flex items-center justify-between rounded bg-background px-3 py-2">
              <span className="font-mono text-sm">{variable.key}</span>
              <Tag variant="purple" size="sm">
                {experimentValue}
              </Tag>
            </div>
          </div>

          <Separator />

          <div className="space-y-4">
            <div className="flex items-center gap-2">
              <Terminal className="size-5 text-muted-slate" />
              <h3 className="font-medium">Run it Yourself</h3>
            </div>
            <p className="text-sm text-muted-slate">
              Include this header in your HTTP request:
            </p>
            <div className="rounded-lg border bg-muted/30 p-4">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-medium uppercase text-muted-slate">
                  HTTP Header
                </span>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  onClick={handleCopyHeader}
                >
                  <Copy className="size-4" />
                </Button>
              </div>
              <code className="block break-all rounded bg-background p-3 font-mono text-sm">
                X-Opik-Experiment-Id: {createdExperimentId}
              </code>
            </div>
          </div>
        </div>
      </div>
    </div>
  );

  const renderExperimentContent = () => (
    <div className="flex h-full flex-col">
      <div className="flex-1 overflow-auto p-6">
        <div className="space-y-6">
          <div>
            <h2 className="comet-title-l mb-1">New Experiment</h2>
            <p className="text-sm text-muted-slate">
              Create a masked value for{" "}
              <code className="font-mono">{variable.key}</code> that will only
              be used when triggered with the experiment ID.
            </p>
          </div>

          <Separator />

          <div className="space-y-4">
            <div className="space-y-2">
              <Label>Experiment Value</Label>
              {variable.type === "boolean" ? (
                <div className="flex gap-2">
                  <Button
                    variant={experimentValue === "true" ? "default" : "outline"}
                    size="sm"
                    onClick={() => setExperimentValue("true")}
                  >
                    True
                  </Button>
                  <Button
                    variant={
                      experimentValue === "false" ? "default" : "outline"
                    }
                    size="sm"
                    onClick={() => setExperimentValue("false")}
                  >
                    False
                  </Button>
                </div>
              ) : variable.type === "number" ? (
                <Input
                  type="number"
                  value={experimentValue}
                  onChange={(e) => setExperimentValue(e.target.value)}
                  step={variable.key === "temperature" ? 0.1 : 1}
                />
              ) : (
                <Input
                  value={experimentValue}
                  onChange={(e) => setExperimentValue(e.target.value)}
                />
              )}
            </div>

            <div className="rounded-md border bg-muted/30 p-3">
              <p className="text-xs text-muted-slate">
                Current production value:{" "}
                <span className="font-mono font-medium">
                  {String(variable.currentValue)}
                </span>
              </p>
            </div>
          </div>
        </div>
      </div>

      <div className="border-t bg-background p-4">
        <Button
          className="w-full"
          onClick={handleCreateExperiment}
          disabled={createMutation.isPending}
        >
          <FlaskConical className="mr-1.5 size-4" />
          {createMutation.isPending ? "Creating..." : "Create Experiment"}
        </Button>
      </div>
    </div>
  );

  const renderContent = () => {
    if (mode === "experiment" && createdExperimentId) {
      return renderExperimentInstructions();
    }
    return mode === "update" ? renderUpdateContent() : renderExperimentContent();
  };

  return (
    <ResizableSidePanel
      panelId="configuration"
      entity="variable"
      open={open}
      onClose={onClose}
      initialWidth={0.4}
      minWidth={400}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default ConfigurationSidePanel;
