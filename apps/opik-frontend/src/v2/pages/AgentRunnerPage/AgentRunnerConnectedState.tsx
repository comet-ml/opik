import React, { useCallback, useMemo, useRef, useState } from "react";
import { Loader2 } from "lucide-react";

import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import useConfigHistoryListInfinite from "@/api/agent-configs/useConfigHistoryListInfinite";
import useAgentConfigCreateMutation from "@/api/agent-configs/useAgentConfigCreateMutation";
import { LocalRunner } from "@/types/agent-sandbox";
import AgentRunnerInputForm from "./AgentRunnerInputForm";
import AgentConfigurationEditView, {
  AgentConfigurationEditViewHandle,
} from "@/v2/pages-shared/agent-configuration/AgentConfigurationEditView";
import { LoadableSelectBox } from "@/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";

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
  const configEditRef = useRef<AgentConfigurationEditViewHandle>(null);
  const { mutateAsync: createConfigAsync } = useAgentConfigCreateMutation();

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

  const versionOptions: DropdownOption<string>[] = useMemo(
    () =>
      allVersions.map((v) => ({
        value: v.id,
        label:
          v.tags?.length > 0 ? `${v.name} (${v.tags.join(" · ")})` : v.name,
      })),
    [allVersions],
  );

  const agent = runner.agents?.[0];
  const inputFields = agent?.params ?? [];

  const handleRun = useCallback(
    async (inputs: Record<string, unknown>) => {
      const editView = configEditRef.current;
      if (editView?.hasChanges()) {
        const payload = await editView.buildMaskPayload();
        if (!payload) return;
        try {
          const { id } = await createConfigAsync({ agentConfig: payload });
          onRun(inputs, id);
        } catch {
          return;
        }
      } else {
        onRun(inputs);
      }
    },
    [onRun, createConfigAsync],
  );

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
          {agent ? (
            <AgentRunnerInputForm
              fields={inputFields}
              onSubmit={handleRun}
              isRunning={isRunning}
            />
          ) : (
            <div className="flex flex-col items-center gap-2 py-8 text-muted-slate">
              <Loader2 className="size-5 animate-spin text-primary" />
              <p className="comet-body-s">Loading agent...</p>
            </div>
          )}
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
              ref={configEditRef}
              item={activeVersion}
              projectId={projectId}
              onSaved={() => setSelectedVersionId("")}
              headerLeft={
                <LoadableSelectBox
                  value={selectedVersionId || activeVersion.id}
                  onChange={setSelectedVersionId}
                  options={versionOptions}
                  buttonClassName="h-6 w-auto px-2 text-xs"
                  align="start"
                  minWidth={200}
                  renderTitle={(option) => (
                    <div className="flex items-center gap-1 truncate">
                      <span>Configuration:</span>
                      <span className="truncate">{option.label}</span>
                    </div>
                  )}
                />
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
