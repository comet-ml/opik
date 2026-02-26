import React, { useState } from "react";
import { Loader2, Play, PlugZap } from "lucide-react";

import { Button } from "@/components/ui/button";
import useMyRunner, { getStoredRunnerId } from "@/api/runners/useMyRunner";
import useCreateRunnerJobMutation from "@/api/runners/useCreateRunnerJobMutation";
import PairingDialog from "@/components/pages/TracesPage/ExecutionTab/PairingDialog";
import { useToast } from "@/components/ui/use-toast";
import { AgentInfo } from "@/types/runners";
import pythonLogoUrl from "/images/integrations/python.png";

type InvocationTabProps = {
  projectId: string;
  projectName: string;
};

const FORM_FILLABLE_TYPES = new Set(["str", "int", "float", "bool"]);

const AgentInvokeForm: React.FunctionComponent<{
  agent: AgentInfo;
  runnerId: string;
  projectName: string;
}> = ({ agent, runnerId, projectName }) => {
  const [paramValues, setParamValues] = useState<Record<string, string>>({});
  const createJobMutation = useCreateRunnerJobMutation();
  const { toast } = useToast();

  const params = agent.params ?? [];
  const hasUnsupported = params.some((p) => !FORM_FILLABLE_TYPES.has(p.type));

  const handleRun = () => {
    const inputs: Record<string, unknown> = {};
    for (const p of params) {
      const raw = paramValues[p.name];
      if (!raw && raw !== "0") continue;
      if (p.type === "int" || p.type === "float") {
        inputs[p.name] = Number(raw);
      } else if (p.type === "bool") {
        inputs[p.name] = raw === "true" || raw === "1";
      } else {
        inputs[p.name] = raw;
      }
    }
    createJobMutation.mutate(
      {
        agent_name: agent.name,
        inputs,
        project: projectName,
        runner_id: runnerId,
      },
      {
        onSuccess: () => {
          toast({
            title: "Job created",
            description: `Running ${agent.name}`,
          });
          setParamValues({});
        },
      },
    );
  };

  if (hasUnsupported) {
    return (
      <div className="rounded-md bg-muted/40 px-4 py-3 text-xs text-muted-slate">
        Cannot invoke manually — has parameters with complex types. Use the
        Replay button on a trace instead.
      </div>
    );
  }

  return (
    <div className="rounded-lg border">
      <div className="flex items-center justify-between border-b bg-muted/30 px-4 py-2">
        <span className="text-xs font-medium text-muted-slate">
          Invoke
        </span>
        <button
          onClick={handleRun}
          disabled={createJobMutation.isPending}
          className="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
        >
          {createJobMutation.isPending ? (
            <Loader2 className="size-3 animate-spin" />
          ) : (
            <Play className="size-3" />
          )}
          Run
        </button>
      </div>
      <div className="divide-y">
        {params.map((p) => (
          <div key={p.name} className="flex items-center">
            <div className="flex w-40 shrink-0 items-center gap-2 border-r px-4 py-2.5">
              <span className="text-xs font-medium">{p.name}</span>
              <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-slate">
                {p.type}
              </span>
            </div>
            <input
              type="text"
              className="min-w-0 flex-1 bg-transparent px-4 py-2.5 text-sm outline-none placeholder:text-muted-slate/50"
              placeholder={
                p.type === "bool"
                  ? "true / false"
                  : `Enter ${p.type} value...`
              }
              value={paramValues[p.name] ?? ""}
              onChange={(e) =>
                setParamValues((prev) => ({
                  ...prev,
                  [p.name]: e.target.value,
                }))
              }
              onKeyDown={(e) => {
                if (e.key === "Enter") handleRun();
              }}
            />
          </div>
        ))}
      </div>
    </div>
  );
};

const InvocationTab: React.FunctionComponent<InvocationTabProps> = ({
  projectName,
}) => {
  const [pairingOpen, setPairingOpen] = useState(false);

  const { data: runner } = useMyRunner({
    refetchInterval: 5000,
  });

  const runnerId = getStoredRunnerId();
  const isConnected = runner?.status === "connected";

  const projectAgents = (runner?.agents ?? []).filter(
    (a) => a.project === projectName,
  );

  const hasAgents = isConnected && projectAgents.length > 0;

  if (hasAgents) {
    return (
      <div className="p-6">
        <div className="mb-6 flex items-center gap-2">
          <PlugZap className="size-4 text-green-600" />
          <span className="text-sm font-medium">
            {runner!.name}
          </span>
          <span className="size-2 rounded-full bg-green-500" />
          <span className="text-sm text-muted-slate">Connected</span>
        </div>

        <div className="mb-3 text-sm font-medium">
          Agents registered for this project
        </div>
        <div className="space-y-4">
          {projectAgents.map((agent) => (
            <div
              key={agent.name}
              className="overflow-hidden rounded-md border"
            >
              <div className="flex items-center gap-2 border-b bg-muted/30 px-4 py-2.5">
                <img src={pythonLogoUrl} alt="Python" className="size-4" />
                <span className="font-mono text-sm font-medium">
                  {agent.name}
                </span>
                <span className="font-mono text-sm text-muted-slate">
                  ({agent.params?.map((p) => `${p.name}: ${p.type}`).join(", ") ?? ""})
                </span>
              </div>
              <div className="px-4 py-3">
                {(agent.file || agent.python) && (
                  <div className="mb-3 flex flex-wrap gap-x-5 gap-y-1 text-xs">
                    {agent.file && (
                      <div className="flex items-center gap-1.5">
                        <span className="text-muted-slate">Source</span>
                        <span className="truncate font-mono">{agent.file}</span>
                      </div>
                    )}
                    {agent.python && (
                      <div className="flex items-center gap-1.5">
                        <span className="text-muted-slate">Interpreter</span>
                        <span className="truncate font-mono">{agent.python}</span>
                      </div>
                    )}
                  </div>
                )}
                {agent.docstring && (
                  <div className="mb-4 text-sm leading-relaxed">
                    {agent.docstring.split("\n\n")[0]}
                  </div>
                )}
                {agent.params && agent.params.length > 0 && (
                  <div className="mb-4">
                    <div className="mb-2 border-b pb-1 text-xs font-semibold uppercase tracking-wide text-muted-slate">
                      Parameters
                    </div>
                    <dl className="space-y-2.5">
                      {agent.params.map((p) => (
                        <div key={p.name}>
                          <dt className="text-sm">
                            <span className="font-semibold">{p.name}</span>
                            <span className="mx-1 text-muted-slate">:</span>
                            <span className="italic text-muted-slate">{p.type}</span>
                          </dt>
                          <dd className="mt-0.5 pl-4 text-xs leading-relaxed text-muted-slate">
                            No description available.
                          </dd>
                        </div>
                      ))}
                    </dl>
                  </div>
                )}
                {runnerId && (
                  <AgentInvokeForm
                    agent={agent}
                    runnerId={runnerId}
                    projectName={projectName}
                  />
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <>
      <div className="mx-auto max-w-lg py-12">
        <h3 className="mb-6 text-base font-medium">
          Set up agent invocation
        </h3>

        <div className="space-y-6">
          <div>
            <div className="mb-2 text-sm font-medium">
              1. Define an agent entry point
            </div>
            <div className="mb-2 text-sm text-muted-slate">
              Use the <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">@entrypoint</code> decorator
              to register an agent for this project:
            </div>
            <pre className="overflow-x-auto rounded-md bg-muted p-4 font-mono text-sm">
{`from opik.agent import entrypoint

@entrypoint(project="${projectName}")
def my_agent(input: str) -> str:
    # your agent logic
    ...`}
            </pre>
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">
              2. Connect your machine
            </div>
            {isConnected ? (
              <div className="flex items-center gap-2 text-sm text-muted-slate">
                <span className="size-2 rounded-full bg-green-500" />
                Connected to {runner!.name} — but no agents are registered for
                this project yet.
              </div>
            ) : (
              <div className="flex items-center gap-3">
                <span className="text-sm text-muted-slate">
                  {runnerId
                    ? "Runner disconnected."
                    : "No runner connected."}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPairingOpen(true)}
                >
                  Connect
                </Button>
              </div>
            )}
          </div>
        </div>
      </div>

      <PairingDialog open={pairingOpen} setOpen={setPairingOpen} />
    </>
  );
};

export default InvocationTab;
