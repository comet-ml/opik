import React, { useEffect, useMemo, useRef, useState } from "react";
import { Info, Pencil, Split } from "lucide-react";

import { BlueprintValueType, ConfigHistoryItem } from "@/types/agent-configs";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import Loader from "@/components/shared/Loader/Loader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import BlueprintTypeIcon from "@/components/pages-shared/traces/ConfigurationTab/BlueprintTypeIcon";
import BlueprintValuePrompt from "@/components/pages-shared/traces/ConfigurationTab/BlueprintValuePrompt";
import { Separator } from "@/components/ui/separator";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useConfigurationSave } from "./useConfigurationSave";
import BlueprintDiffDialog from "./BlueprintDiffDialog/BlueprintDiffDialog";

type ConfigurationEditViewProps = {
  item: ConfigHistoryItem;
  projectId: string;
  version: number;
  latestVersion: number;
  onCancel: () => void;
  onSaved: () => void;
};

const ConfigurationEditView: React.FC<ConfigurationEditViewProps> = ({
  item,
  projectId,
  version,
  latestVersion,
  onCancel,
  onSaved,
}) => {
  const { data: agentConfig, isPending } = useAgentConfigById({
    blueprintId: item.id,
  });

  const [description, setDescription] = useState("");
  const [draftValues, setDraftValues] = useState<Record<string, string>>({});
  const [dirtyPromptKeys, setDirtyPromptKeys] = useState<
    Record<string, boolean>
  >({});
  const originalValues = useRef<Record<string, string>>({});
  const initialized = useRef(false);
  const isLatestVersion = version === latestVersion;

  const { handleSave, isSaving, errors, clearError, promptRefs } =
    useConfigurationSave({
      agentConfig,
      draftValues,
      originalValues,
      description,
      projectId,
      isLatestVersion,
      onSaved,
    });

  useEffect(() => {
    if (agentConfig && !initialized.current) {
      initialized.current = true;
      const initial: Record<string, string> = {};
      agentConfig.values
        .filter((v) => v.type !== BlueprintValueType.PROMPT)
        .forEach((v) => {
          initial[v.key] = v.value;
        });
      originalValues.current = initial;
      setDraftValues(initial);
    }
  }, [agentConfig]);

  const handleFieldChange = (key: string, value: string) => {
    setDraftValues((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      clearError(key);
    }
  };

  const [diffOpen, setDiffOpen] = useState(false);
  const [diffPromptTemplates, setDiffPromptTemplates] = useState<
    Record<string, string>
  >({});

  const currentValues = useMemo(() => {
    if (!agentConfig) return [];
    return agentConfig.values.map((v) =>
      v.type === BlueprintValueType.PROMPT
        ? v
        : { ...v, value: draftValues[v.key] ?? v.value },
    );
  }, [agentConfig, draftValues]);

  const handleShowDiff = () => {
    const templates: Record<string, string> = {};
    for (const [key, handle] of Object.entries(promptRefs.current)) {
      if (handle && dirtyPromptKeys[key]) {
        templates[key] = handle.getCurrentTemplate();
      }
    }
    setDiffPromptTemplates(templates);
    setDiffOpen(true);
  };

  const hasErrors = Object.values(errors).some(Boolean);

  const hasChanges =
    Object.keys(draftValues).some(
      (key) => draftValues[key] !== originalValues.current[key],
    ) || Object.values(dirtyPromptKeys).some(Boolean);

  if (isPending) {
    return <Loader />;
  }

  return (
    <Card className="mx-6 my-4 p-6">
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="comet-title-s">Create new version</h2>
          <div className="comet-body-xs flex items-center gap-1 rounded bg-[var(--edit-badge-bg)] px-2 py-0.5 text-[var(--edit-badge-text)]">
            <Pencil className="size-2.5" />
            From v{version}
          </div>
          <Button
            variant="outline"
            size="xs"
            onClick={handleShowDiff}
            disabled={!hasChanges}
          >
            <Split className="mr-1 size-3.5" />
            Show diff
          </Button>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={onCancel}
            disabled={isSaving}
          >
            Cancel
          </Button>
          <TooltipWrapper
            content={
              !hasChanges ? "Make changes to save a new version" : undefined
            }
          >
            <span className="inline-flex">
              <Button
                size="sm"
                onClick={handleSave}
                disabled={isSaving || hasErrors || !hasChanges}
              >
                {isSaving ? "Saving…" : "Save as new version"}
              </Button>
            </span>
          </TooltipWrapper>
        </div>
      </div>

      {version !== latestVersion && (
        <Alert variant="callout" size="sm" className="mb-4">
          <Info />
          <AlertDescription size="sm">
            You&apos;re creating a version from v{version}. More recent versions
            contain prompt updates that won&apos;t be included.
          </AlertDescription>
        </Alert>
      )}

      <div className="mb-4">
        <label className="comet-body-xs-accented mb-1.5 block text-foreground">
          Description
        </label>
        <Input
          placeholder="Describe what changed in this version…"
          value={description}
          dimension="sm"
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>

      <Separator orientation="horizontal" />

      <div className="flex flex-col divide-y">
        {(agentConfig?.values ?? []).map((v) => {
          const isChanged =
            v.type === BlueprintValueType.PROMPT
              ? !!dirtyPromptKeys[v.key]
              : draftValues[v.key] !== undefined &&
                draftValues[v.key] !== originalValues.current[v.key];
          return (
            <div key={v.key} className="flex flex-col gap-2 py-4">
              <div className="flex items-center gap-2">
                <BlueprintTypeIcon type={v.type} variant="secondary" />
                <span className="comet-body-s-accented text-foreground">
                  {v.key}
                </span>
                {v.type === BlueprintValueType.PROMPT && isChanged && (
                  <TooltipWrapper content="Saving will create a new prompt version visible everywhere in the platform">
                    <Info className="size-3.5 text-muted-slate" />
                  </TooltipWrapper>
                )}
                {isChanged && (
                  <span className="size-1.5 rounded-full bg-amber-400" />
                )}
              </div>
              {v.description && (
                <TooltipWrapper content={v.description}>
                  <span className="comet-body-xs w-fit max-w-full truncate text-light-slate">
                    {v.description}
                  </span>
                </TooltipWrapper>
              )}
              {v.type === BlueprintValueType.PROMPT ? (
                <div className="flex flex-col gap-1">
                  <BlueprintValuePrompt
                    key={v.value}
                    value={v}
                    projectId={projectId}
                    isEditing={isLatestVersion}
                    ref={(el) => {
                      promptRefs.current[v.key] = el;
                    }}
                    onDirtyChange={(isDirty) => {
                      setDirtyPromptKeys((prev) => ({
                        ...prev,
                        [v.key]: isDirty,
                      }));
                      clearError(v.key);
                    }}
                  />
                  {errors[v.key] && (
                    <span className="comet-body-xs text-destructive">
                      {errors[v.key]}
                    </span>
                  )}
                </div>
              ) : v.type === BlueprintValueType.BOOLEAN ? (
                <Switch
                  checked={draftValues[v.key] === "true"}
                  onCheckedChange={(checked) =>
                    setDraftValues((prev) => ({
                      ...prev,
                      [v.key]: String(checked),
                    }))
                  }
                />
              ) : (
                <div className="flex flex-col gap-1">
                  <Input
                    inputMode={
                      v.type === BlueprintValueType.INT
                        ? "numeric"
                        : v.type === BlueprintValueType.FLOAT
                          ? "decimal"
                          : "text"
                    }
                    value={draftValues[v.key] ?? ""}
                    onChange={(e) => handleFieldChange(v.key, e.target.value)}
                  />
                  {errors[v.key] && (
                    <span className="comet-body-xs text-destructive">
                      {errors[v.key]}
                    </span>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
      <BlueprintDiffDialog
        open={diffOpen}
        setOpen={setDiffOpen}
        base={{
          label: `v${version} (original)`,
          blueprintId: item.id,
        }}
        diff={{
          label: "Current edits",
          blueprintId: item.id,
          values: currentValues,
          description,
          promptTemplates: diffPromptTemplates,
        }}
      />
    </Card>
  );
};

export default ConfigurationEditView;
