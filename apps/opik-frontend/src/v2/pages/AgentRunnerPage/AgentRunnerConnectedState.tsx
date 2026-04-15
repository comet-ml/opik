import React, { useCallback, useMemo, useRef, useState } from "react";
import { ArrowRight, ArrowUpRight, Loader2, Save } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";

import { AGENT_CONFIGS_KEY } from "@/api/api";
import { ConfigHistoryItem } from "@/types/agent-configs";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import { Button } from "@/ui/button";
import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import useAppStore from "@/store/AppStore";
import useConfigHistoryListInfinite from "@/api/agent-configs/useConfigHistoryListInfinite";
import useAgentConfigCreateMutation from "@/api/agent-configs/useAgentConfigCreateMutation";
import { LocalRunner } from "@/types/agent-sandbox";
import AgentRunnerInputForm from "./AgentRunnerInputForm";
import AgentConfigurationEditView, {
  AgentConfigurationEditViewHandle,
  AgentConfigurationEditViewState,
} from "@/v2/pages-shared/agent-configuration/AgentConfigurationEditView";
import ExpandAllToggle from "@/v2/pages-shared/agent-configuration/fields/ExpandAllToggle";
import { useFieldsCollapse } from "@/v2/pages-shared/agent-configuration/fields/useFieldsCollapse";
import { LoadableSelectBox } from "@/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";

type AgentRunnerConnectedStateProps = {
  projectId: string;
  runner: LocalRunner;
  onRun: (
    inputs: Record<string, unknown>,
    blueprintName?: string,
    maskId?: string,
  ) => void;
  isRunning: boolean;
  resetKey: number;
};

const AgentRunnerConnectedState: React.FC<AgentRunnerConnectedStateProps> = ({
  projectId,
  runner,
  onRun,
  isRunning,
  resetKey,
}) => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [activeTab, setActiveTab] = useState("input");
  const [selectedVersionId, setSelectedVersionId] = useState<string>("");
  const configEditRef = useRef<AgentConfigurationEditViewHandle>(null);
  const { mutateAsync: createConfigAsync } = useAgentConfigCreateMutation();

  const [editState, setEditState] = useState<AgentConfigurationEditViewState>({
    isDirty: false,
    isSaving: false,
    hasErrors: false,
    collapsibleKeys: [],
    initiallyExpandedKeys: [],
    hasExpandableFields: false,
  });

  const controller = useFieldsCollapse({
    collapsibleKeys: editState.collapsibleKeys,
  });

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
      const blueprintName = activeVersion?.name;
      if (editView?.hasChanges()) {
        const payload = await editView.buildMaskPayload();
        if (!payload) return;
        try {
          const { id } = await createConfigAsync({ agentConfig: payload });
          onRun(inputs, blueprintName, id);
        } catch {
          return;
        }
      } else {
        onRun(inputs, blueprintName);
      }
    },
    [onRun, createConfigAsync, activeVersion],
  );

  const handleSaveConfiguration = useCallback(async () => {
    await configEditRef.current?.save();
  }, []);

  const queryClient = useQueryClient();

  const resolveSavedVersionName = useCallback(
    async (newBlueprintId?: string): Promise<string | undefined> => {
      if (!newBlueprintId) return undefined;
      const fromCurrent = allVersions.find((v) => v.id === newBlueprintId);
      if (fromCurrent) return fromCurrent.name;
      const queryKey = [AGENT_CONFIGS_KEY, "history", { projectId }];
      await queryClient.refetchQueries({ queryKey });
      type Page = { content: ConfigHistoryItem[] };
      const data = queryClient.getQueryData<{ pages: Page[] }>(queryKey);
      const found = data?.pages
        ?.flatMap((p) => p.content)
        ?.find((v) => v.id === newBlueprintId);
      return found?.name;
    },
    [allVersions, queryClient, projectId],
  );

  const handleConfigSaved = useCallback(
    async (newBlueprintId?: string) => {
      setSelectedVersionId("");
      const versionName = await resolveSavedVersionName(newBlueprintId);
      const description = versionName
        ? `You've created ${versionName} of agent configuration. You can deploy it from Agent configuration page.`
        : "You've created a new version of agent configuration. You can deploy it from Agent configuration page.";
      toast({
        title: "New agent configuration saved",
        description,
        actions: [
          <ToastAction
            key="manage"
            variant="link"
            size="sm"
            altText="Manage agent configuration"
            onClick={() =>
              navigate({
                to: "/$workspaceName/projects/$projectId/agent-configuration",
                params: { workspaceName, projectId },
              })
            }
          >
            Manage agent configuration
            <ArrowRight className="ml-1 size-3.5" />
          </ToastAction>,
        ],
      });
    },
    [toast, navigate, workspaceName, projectId, resolveSavedVersionName],
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
              key={resetKey}
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
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center gap-2">
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
                {editState.isDirty && (
                  <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
                    <span className="size-1.5 rounded-full bg-destructive" />
                    Edited
                  </span>
                )}
                <div className="ml-auto flex items-center gap-1">
                  {editState.hasExpandableFields && (
                    <ExpandAllToggle controller={controller} />
                  )}
                  <Button
                    variant="outline"
                    size="2xs"
                    onClick={handleSaveConfiguration}
                    disabled={
                      editState.isSaving ||
                      editState.hasErrors ||
                      !editState.isDirty
                    }
                  >
                    <Save className="mr-1 size-3.5" />
                    {editState.isSaving ? "Saving…" : "Save configuration"}
                    <ArrowUpRight className="ml-1 size-3.5" />
                  </Button>
                </div>
              </div>

              <AgentConfigurationEditView
                key={`${activeVersion.id}-${resetKey}`}
                ref={configEditRef}
                item={activeVersion}
                projectId={projectId}
                onSaved={handleConfigSaved}
                controller={controller}
                onStateChange={setEditState}
                blockNavigation={false}
              />
            </div>
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
