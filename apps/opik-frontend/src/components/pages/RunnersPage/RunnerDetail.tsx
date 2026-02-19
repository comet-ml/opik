import React, { useState } from "react";
import { ChevronDown, ChevronRight, Cpu } from "lucide-react";

import { Runner } from "@/types/runners";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import RunnerJobsTable from "./RunnerJobsTable";

type RunnerDetailProps = {
  runner: Runner;
};

const RunnerDetail: React.FunctionComponent<RunnerDetailProps> = ({
  runner,
}) => {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="rounded-lg border">
      <button
        className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-muted/50"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <ChevronDown className="size-4 shrink-0 text-muted-slate" />
        ) : (
          <ChevronRight className="size-4 shrink-0 text-muted-slate" />
        )}
        <Cpu className="size-4 shrink-0" />
        <div className="flex-1">
          <div className="font-medium">{runner.name}</div>
          <div className="comet-body-xs text-muted-slate">
            {runner.agents?.length ?? 0} agent(s) registered
          </div>
        </div>
        <div
          className={cn(
            "rounded-full px-2 py-0.5 text-xs font-medium",
            runner.status === "connected"
              ? "bg-green-100 text-green-700"
              : "bg-gray-100 text-gray-600",
          )}
        >
          {runner.status}
        </div>
      </button>

      {expanded && (
        <div className="border-t px-4 py-3 space-y-3">
          {runner.agents && runner.agents.length > 0 && (
            <div>
              <div className="comet-body-s mb-2 font-medium">
                Registered Agents
              </div>
              <div className="space-y-1">
                {runner.agents.map((agent) => (
                  <div
                    key={agent.name}
                    className="flex items-center justify-between rounded-md bg-muted/50 px-3 py-1.5 text-sm"
                  >
                    <span className="font-mono">{agent.name}</span>
                    <span className="text-muted-slate">
                      project: {agent.project}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div>
            <div className="comet-body-s mb-2 font-medium">Runs</div>
            <RunnerJobsTable runnerId={runner.id} />
          </div>
        </div>
      )}
    </div>
  );
};

export default RunnerDetail;
