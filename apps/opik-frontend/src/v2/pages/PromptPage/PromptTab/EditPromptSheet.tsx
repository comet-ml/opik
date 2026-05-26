import React, { useCallback, useEffect, useRef, useState } from "react";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { AutoGrowTextarea } from "@/ui/auto-grow-textarea";
import { Label } from "@/ui/label";
import { Button } from "@/ui/button";
import PromptMetadataEditor from "@/v2/pages-shared/llm/PromptMetadataEditor/PromptMetadataEditor";
import PromptTemplateEditor from "@/v2/pages-shared/llm/PromptTemplateEditor/PromptTemplateEditor";
import { usePromptTemplateEditor } from "@/v2/pages-shared/llm/PromptTemplateEditor/usePromptTemplateEditor";
import { useActiveProjectId } from "@/store/AppStore";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { PROMPT_TEMPLATE_STRUCTURE, PROMPT_TYPE } from "@/types/prompts";

type EditPromptSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  template: string;
  metadata?: object;
  promptName: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  type?: PROMPT_TYPE;
  onSetActiveVersionId: (versionId: string) => void;
};

const EditPromptSheet: React.FC<EditPromptSheetProps> = ({
  open,
  setOpen,
  template: promptTemplate,
  metadata: promptMetadata,
  promptName,
  templateStructure,
  type: promptType,
  onSetActiveVersionId,
}) => {
  const activeProjectId = useActiveProjectId();

  const metadataString = promptMetadata
    ? JSON.stringify(promptMetadata, null, 2)
    : "";
  const [metadata, setMetadata] = useState(metadataString);
  const [changeDescription, setChangeDescription] = useState("");

  const editor = usePromptTemplateEditor({
    initialTemplate: promptTemplate,
    templateStructure: templateStructure ?? PROMPT_TEMPLATE_STRUCTURE.TEXT,
    open,
  });

  // Reset metadata + change description each time the sheet reopens; editor
  // state has its own reset in usePromptTemplateEditor.
  const latestMetadataRef = useRef(metadataString);
  latestMetadataRef.current = metadataString;
  useEffect(() => {
    if (!open) return;
    setMetadata(latestMetadataRef.current);
    setChangeDescription("");
  }, [open]);

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});

  const { mutate, isPending: isSaving } = useCreatePromptVersionMutation();

  const metadataHasChanges = metadata !== metadataString;
  const isValid = editor.isValid && (editor.isDirty || metadataHasChanges);

  const handleClickEditPrompt = useCallback(() => {
    if (!isValid || isSaving) return;
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    mutate({
      name: promptName,
      template: editor.serialize(),
      changeDescription,
      ...(metadata && { metadata: safelyParseJSON(metadata) }),
      ...(templateStructure && { templateStructure }),
      ...(promptType && { type: promptType }),
      projectId: activeProjectId ?? undefined,
      onSuccess: (data) => {
        onSetActiveVersionId(data.id);
      },
    });

    setOpen(false);
  }, [
    isValid,
    isSaving,
    metadata,
    editor,
    mutate,
    promptName,
    changeDescription,
    templateStructure,
    promptType,
    activeProjectId,
    onSetActiveVersionId,
    setOpen,
    setShowInvalidJSON,
  ]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        handleClickEditPrompt();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, handleClickEditPrompt]);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[720px]"
        header={<SheetTopBar variant="form" title="Edit prompt" />}
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6">
          <PromptTemplateEditor editor={editor} />

          <PromptMetadataEditor
            value={metadata}
            onChange={setMetadata}
            showInvalidJSON={showInvalidJSON}
          />

          <div className="space-y-1.5">
            <Label htmlFor="promptVersionNotes">Version notes</Label>
            <AutoGrowTextarea
              id="promptVersionNotes"
              dimension="sm"
              className="comet-body-s"
              value={changeDescription}
              onChange={setChangeDescription}
              placeholder="Describe what changed in this version"
            />
          </div>
        </div>
        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button variant="outline" size="sm" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            type="submit"
            disabled={!isValid || isSaving}
            onClick={handleClickEditPrompt}
          >
            Create new commit
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default EditPromptSheet;
