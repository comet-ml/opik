import React, {
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import { Plus, RotateCcw, Trash } from "lucide-react";
import { Button } from "@/ui/button";
import { serializeChatTemplate } from "@/lib/chatTemplate";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import {
  BlueprintValue,
  BlueprintValueType,
  ConfigHistoryItem,
} from "@/types/agent-configs";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import { Input } from "@/ui/input";
import { Switch } from "@/ui/switch";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BlueprintTypeIcon from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintTypeIcon";
import BlueprintValuePromptCompact from "./fields/BlueprintValuePromptCompact";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import FieldSection from "./fields/FieldSection";
import {
  collectMultiLineKeys,
  hasAnyExpandableField,
  isMultiLineField,
} from "./fields/blueprintFieldLayout";
import {
  FieldsCollapseController,
  useFieldsCollapse,
} from "./fields/useFieldsCollapse";
import BlueprintDiffTable from "./BlueprintDiffDialog/BlueprintDiffTable";
import {
  useAgentConfigurationSave,
  AgentConfigPayload,
} from "./useAgentConfigurationSave";
import NewBlueprintFieldEditor, {
  NewFieldDraft,
  createNewFieldDraft,
} from "./NewBlueprintFieldEditor";

export type AgentConfigurationEditViewHandle = {
  hasChanges: () => boolean;
  buildMaskPayload: () => Promise<AgentConfigPayload | null>;
  save: () => Promise<void>;
};

export type AgentConfigurationEditViewState = {
  isDirty: boolean;
  isSaving: boolean;
  hasErrors: boolean;
  isEmpty: boolean;
  collapsibleKeys: string[];
  hasExpandableFields: boolean;
};

type AgentConfigurationEditViewProps = {
  item: ConfigHistoryItem;
  projectId: string;
  onSaved: (newBlueprintId?: string) => void;
  view?: "edit" | "diff";
  description?: string;
  onDescriptionChange?: (value: string) => void;
  controller?: FieldsCollapseController;
  onStateChange?: (state: AgentConfigurationEditViewState) => void;
  blockNavigation?: boolean;
};

const AgentConfigurationEditView = React.forwardRef<
  AgentConfigurationEditViewHandle,
  AgentConfigurationEditViewProps
>(
  (
    {
      item,
      projectId,
      onSaved,
      view = "edit",
      description: controlledDescription,
      onDescriptionChange,
      controller: externalController,
      onStateChange,
      blockNavigation = true,
    },
    ref,
  ) => {
    const { data: agentConfig, isPending } = useAgentConfigById({
      blueprintId: item.id,
    });

    const [internalDescription, setInternalDescription] = useState("");
    const description = controlledDescription ?? internalDescription;
    const setDescription = useCallback(
      (value: string) => {
        if (onDescriptionChange) {
          onDescriptionChange(value);
        } else {
          setInternalDescription(value);
        }
      },
      [onDescriptionChange],
    );
    const [draftValues, setDraftValues] = useState<Record<string, string>>({});
    const [dirtyPromptKeys, setDirtyPromptKeys] = useState<
      Record<string, boolean>
    >({});
    const [removedKeys, setRemovedKeys] = useState<Set<string>>(new Set());
    const [newFields, setNewFields] = useState<NewFieldDraft[]>([]);
    const originalValues = useRef<Record<string, string>>({});
    const initialized = useRef(false);
    const newFieldIdRef = useRef(0);

    const handleSaveComplete = useCallback(
      (newBlueprintId?: string) => {
        originalValues.current = { ...draftValues };
        setDirtyPromptKeys({});
        setRemovedKeys(new Set());
        setNewFields([]);
        setDescription("");
        onSaved(newBlueprintId);
      },
      [draftValues, onSaved, setDescription],
    );

    const {
      handleSave,
      buildMaskPayload,
      hasChanges,
      isSaving,
      errors,
      clearError,
      promptRefs,
    } = useAgentConfigurationSave({
      agentConfig,
      draftValues,
      originalValues,
      description,
      projectId,
      onSaved: handleSaveComplete,
      dirtyPromptKeys,
      removedKeys,
      newFields,
    });

    useImperativeHandle(ref, () => ({
      hasChanges,
      buildMaskPayload,
      save: handleSave,
    }));

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

    const toggleRemoved = useCallback(
      (key: string) => {
        setRemovedKeys((prev) => {
          const next = new Set(prev);
          if (next.has(key)) next.delete(key);
          else next.add(key);
          return next;
        });
        clearError(key);
      },
      [clearError],
    );

    const handleAddNewField = useCallback(() => {
      const id = `new-${++newFieldIdRef.current}`;
      setNewFields((prev) => [...prev, createNewFieldDraft(id)]);
    }, []);

    const updateNewField = useCallback(
      (next: NewFieldDraft) => {
        setNewFields((prev) => prev.map((f) => (f.id === next.id ? next : f)));
        if (errors[next.id]) clearError(next.id);
      },
      [errors, clearError],
    );

    const removeNewField = useCallback(
      (id: string) => {
        setNewFields((prev) => prev.filter((f) => f.id !== id));
        clearError(id);
      },
      [clearError],
    );

    const collapsibleKeys = useMemo(
      () => collectMultiLineKeys(agentConfig?.values ?? []),
      [agentConfig],
    );

    const internalController = useFieldsCollapse({ collapsibleKeys });
    const controller = externalController ?? internalController;

    const currentValues = useMemo<BlueprintValue[]>(() => {
      if (!agentConfig) return [];
      const kept = agentConfig.values
        .filter((v) => !removedKeys.has(v.key))
        .map((v) =>
          v.type === BlueprintValueType.PROMPT
            ? v
            : { ...v, value: draftValues[v.key] ?? v.value },
        );
      const added: BlueprintValue[] = newFields
        .filter((f) => f.key.trim())
        .map((f) => ({ key: f.key.trim(), type: f.type, value: f.value }));
      return [...kept, ...added];
    }, [agentConfig, draftValues, removedKeys, newFields]);

    const diffPromptTemplates = useMemo<Record<string, string>>(() => {
      const out: Record<string, string> = {};
      for (const [key, handle] of Object.entries(promptRefs.current)) {
        if (handle && dirtyPromptKeys[key]) {
          out[key] = handle.getCurrentTemplate();
        }
      }
      for (const field of newFields) {
        if (field.type !== BlueprintValueType.PROMPT) continue;
        const key = field.key.trim();
        if (!key) continue;
        const isTextPrompt =
          field.promptStructure === PROMPT_TEMPLATE_STRUCTURE.TEXT;
        out[key] = isTextPrompt
          ? field.value
          : serializeChatTemplate(field.messages);
      }
      return out;
    }, [dirtyPromptKeys, promptRefs, newFields]);

    const hasErrors = Object.values(errors).some(Boolean);
    const isDirty = hasChanges();

    const isEmpty = useMemo(() => {
      const keptCount =
        (agentConfig?.values ?? []).filter((v) => !removedKeys.has(v.key))
          .length + newFields.length;
      return keptCount === 0;
    }, [agentConfig, removedKeys, newFields]);

    const hasExpandableFields = useMemo(
      () => hasAnyExpandableField(agentConfig?.values ?? []),
      [agentConfig],
    );

    useEffect(() => {
      onStateChange?.({
        isDirty,
        isSaving,
        hasErrors,
        isEmpty,
        collapsibleKeys,
        hasExpandableFields,
      });
    }, [
      isDirty,
      isSaving,
      hasErrors,
      isEmpty,
      collapsibleKeys,
      hasExpandableFields,
      onStateChange,
    ]);

    const { DialogComponent } = useNavigationBlocker({
      condition: blockNavigation && isDirty,
      title: "You have unsaved changes",
      description:
        "If you leave now, your changes will be lost. Are you sure you want to continue?",
      confirmText: "Leave without saving",
      cancelText: "Stay on page",
    });

    if (isPending) {
      return <Loader />;
    }

    if (view === "diff") {
      return (
        <>
          <BlueprintDiffTable
            base={{
              label: `${item.name} (original)`,
              blueprintId: item.id,
              values: agentConfig?.values,
              description: item.description,
            }}
            diff={{
              label: "Current changes",
              blueprintId: item.id,
              values: currentValues,
              promptTemplates: diffPromptTemplates,
              description,
            }}
            defaultOnlyDiff
          />
          {DialogComponent}
        </>
      );
    }

    return (
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-4">
          {(agentConfig?.values ?? []).map((v) => {
            const isPrompt = v.type === BlueprintValueType.PROMPT;
            const collapsible = isMultiLineField(v);
            const isRemoved = removedKeys.has(v.key);
            const isChanged =
              isRemoved ||
              (v.type === BlueprintValueType.PROMPT
                ? !!dirtyPromptKeys[v.key]
                : draftValues[v.key] !== undefined &&
                  draftValues[v.key] !== originalValues.current[v.key]);

            const fieldExpandable = collapsible;
            const fieldExpanded = fieldExpandable
              ? controller.isExpanded(v.key)
              : undefined;
            const isBoolean = v.type === BlueprintValueType.BOOLEAN;

            const modifiedDot = isChanged ? (
              <TooltipWrapper content="Modified">
                <span
                  className="size-1.5 rounded-full bg-amber-400"
                  aria-label="Modified"
                />
              </TooltipWrapper>
            ) : null;

            const booleanSwitch = isBoolean ? (
              <Switch
                size="sm"
                checked={draftValues[v.key] === "true"}
                onCheckedChange={(checked) =>
                  setDraftValues((prev) => ({
                    ...prev,
                    [v.key]: String(checked),
                  }))
                }
              />
            ) : null;

            return (
              <div key={v.key} className={isRemoved ? "opacity-60" : undefined}>
                <FieldSection
                  label={v.key}
                  icon={<BlueprintTypeIcon type={v.type} variant="secondary" />}
                  description={v.description}
                  expandable={fieldExpandable}
                  expanded={fieldExpanded}
                  onToggle={
                    fieldExpandable ? () => controller.toggle(v.key) : undefined
                  }
                  afterLabel={booleanSwitch}
                  trailing={
                    <div className="flex items-center gap-1">
                      {modifiedDot}
                      <TooltipWrapper
                        content={isRemoved ? "Restore field" : "Remove field"}
                      >
                        <Button
                          variant="minimal"
                          size="icon-xs"
                          onClick={() => toggleRemoved(v.key)}
                        >
                          {isRemoved ? <RotateCcw /> : <Trash />}
                        </Button>
                      </TooltipWrapper>
                    </div>
                  }
                >
                  {isRemoved ? null : isPrompt ? (
                    <div className="flex flex-col gap-1">
                      <BlueprintValuePromptCompact
                        key={v.value}
                        value={v}
                        projectId={projectId}
                        isEditing
                        tone="white"
                        expanded={!!fieldExpanded}
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
                  ) : (
                    (() => {
                      if (isBoolean) return null;
                      if (fieldExpandable && !fieldExpanded) return null;

                      const inputMode =
                        v.type === BlueprintValueType.INT
                          ? "numeric"
                          : v.type === BlueprintValueType.FLOAT
                            ? "decimal"
                            : "text";
                      const currentValue = draftValues[v.key] ?? "";
                      const errorLine = errors[v.key] ? (
                        <span className="comet-body-xs text-destructive">
                          {errors[v.key]}
                        </span>
                      ) : null;

                      return (
                        <div className="flex flex-col gap-1">
                          <div className="rounded-md border bg-background px-3 py-2">
                            <Input
                              variant="ghost"
                              className="h-auto p-0"
                              inputMode={inputMode}
                              value={currentValue}
                              onChange={(e) =>
                                handleFieldChange(v.key, e.target.value)
                              }
                            />
                          </div>
                          {errorLine}
                        </div>
                      );
                    })()
                  )}
                </FieldSection>
              </div>
            );
          })}

          {newFields.length > 0 &&
            (() => {
              const existingKeys = new Set(
                agentConfig?.values
                  ?.filter((v) => !removedKeys.has(v.key))
                  ?.map((v) => v.key) ?? [],
              );
              return newFields.map((field) => {
                const reservedKeys = new Set([
                  ...existingKeys,
                  ...newFields
                    .filter((f) => f.id !== field.id)
                    .map((f) => f.key.trim())
                    .filter(Boolean),
                ]);
                return (
                  <NewBlueprintFieldEditor
                    key={field.id}
                    field={field}
                    reservedKeys={reservedKeys}
                    onChange={updateNewField}
                    onRemove={() => removeNewField(field.id)}
                    error={errors[field.id]}
                  />
                );
              });
            })()}

          <div className="flex justify-end">
            <Button variant="outline" size="sm" onClick={handleAddNewField}>
              <Plus className="mr-1 size-3.5" />
              Add field
            </Button>
          </div>
        </div>

        {DialogComponent}
      </div>
    );
  },
);

AgentConfigurationEditView.displayName = "AgentConfigurationEditView";

export default AgentConfigurationEditView;
