import React, { useMemo, useState } from "react";

import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import useConfigHistoryListInfinite from "@/api/agent-configs/useConfigHistoryListInfinite";
import { LocalRunner } from "@/types/agent-sandbox";
import AgentRunnerInputForm from "./AgentRunnerInputForm";
import AgentConfigurationEditView from "@/v2/pages-shared/agent-configuration/AgentConfigurationEditView";

type AgentRunnerConnectedStateProps = {
  projectId: string;
  runner: LocalRunner;
  onRun: (inputs: Record<string, unknown>, maskId?: string) => void;
  isRunning: boolean;
};

const AgentRunnerConnectedState: React.FC<AgentRunnerConnectedStateProps> = ({
  projectId,
  runner,
  onRun,
  isRunning,
}) => {
  const [activeTab, setActiveTab] = useState("input");
  const [selectedVersionId, setSelectedVersionId] = useState<string>("");

  const { data: configData } = useConfigHistoryListInfinite({ projectId });

  const allVersions = useMemo(
    () => configData?.pages?.flatMap((p) => p.content) ?? [],
    [configData],
  );

  const activeVersion = useMemo(() => {
    if (selectedVersionId) {
      return (
        allVersions.find((v) => v.id === selectedVersionId) ??
        allVersions[0] ??
        null
      );
    }
    return allVersions[0] ?? null;
  }, [allVersions, selectedVersionId]);

  const agent = runner.agents?.[0];
  const inputFields = agent?.params ?? [];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <Tabs
        value={activeTab}
        onValueChange={setActiveTab}
        className="flex min-h-0 flex-1 flex-col"
      >
        <TabsList variant="underline" className="shrink-0 px-4">
          <TabsTrigger value="input" variant="underline">
            Input
          </TabsTrigger>
          <TabsTrigger value="configuration" variant="underline">
            Configuration
          </TabsTrigger>
        </TabsList>

        <TabsContent
          value="input"
          className="mt-0 min-h-0 flex-1 overflow-y-auto p-4"
          forceMount
          hidden={activeTab !== "input"}
        >
          <AgentRunnerInputForm
            fields={inputFields}
            onSubmit={onRun}
            isRunning={isRunning}
          />
        </TabsContent>

        <TabsContent
          value="configuration"
          className="mt-0 min-h-0 flex-1 overflow-y-auto p-4"
          forceMount
          hidden={activeTab !== "configuration"}
        >
          {activeVersion ? (
            <AgentConfigurationEditView
              key={activeVersion.id}
              item={activeVersion}
              projectId={projectId}
              onSaved={() => setSelectedVersionId("")}
              headerLeft={
                <Select
                  value={selectedVersionId || activeVersion.id}
                  onValueChange={setSelectedVersionId}
                >
                  <SelectTrigger className="h-6 w-auto gap-1 px-2 text-xs focus:border-input">
                    <span>Configuration:</span>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {allVersions.map((v) => (
                      <SelectItem key={v.id} value={v.id}>
                        {v.name}
                        {v.tags?.length > 0 && (
                          <span className="ml-2 text-muted-slate">
                            ({v.tags.join(" · ")})
                          </span>
                        )}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              }
            />
          ) : (
            <div className="flex flex-col items-center py-8 text-muted-slate">
              <p className="comet-body-s">
                No agent configuration found for this project.
              </p>
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default AgentRunnerConnectedState;
