import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { AutoGrowTextarea } from "@/ui/auto-grow-textarea";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import PromptMetadataEditor from "@/v2/pages-shared/llm/PromptMetadataEditor/PromptMetadataEditor";
import PromptTemplateEditor from "@/v2/pages-shared/llm/PromptTemplateEditor/PromptTemplateEditor";
import { usePromptTemplateEditor } from "@/v2/pages-shared/llm/PromptTemplateEditor/usePromptTemplateEditor";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import { isMessageEmpty } from "@/v2/pages-shared/agent-configuration/useAgentConfigurationSave";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

type CreatePromptSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  templateStructure: PROMPT_TEMPLATE_STRUCTURE;
};

const CreatePromptSheet: React.FC<CreatePromptSheetProps> = ({
  open,
  setOpen,
  templateStructure,
}) => {
  const workspaceName = useAppStore((s) => s.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();

  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  const title = isChatPrompt ? "New chat prompt" : "New text prompt";

  const [name, setName] = useState("");
  const [metadata, setMetadata] = useState("");
  const [description, setDescription] = useState("");
  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});

  const editor = usePromptTemplateEditor({
    initialTemplate: "",
    templateStructure,
    open,
  });

  const { mutate: createMutate, isPending: isCreating } =
    usePromptCreateMutation();

  const isValid = useMemo(() => {
    if (!name.trim().length) return false;
    if (isChatPrompt) {
      const hasNonEmptyMessage = editor.messages.some(
        (m) => !isMessageEmpty(m),
      );
      return (
        hasNonEmptyMessage &&
        (editor.chatViewMode === "pretty" || editor.isRawJsonValid)
      );
    }
    return editor.template.trim().length > 0;
  }, [
    name,
    isChatPrompt,
    editor.messages,
    editor.chatViewMode,
    editor.isRawJsonValid,
    editor.template,
  ]);

  const onPromptCreated = useCallback(
    (prompt: Prompt) => {
      if (!prompt.id) return;
      navigate({
        to: "/$workspaceName/projects/$projectId/prompts/$promptId",
        params: {
          promptId: prompt.id,
          workspaceName,
          projectId: activeProjectId!,
        },
      });
    },
    [navigate, workspaceName, activeProjectId],
  );

  const handleCreate = useCallback(() => {
    if (!isValid || isCreating) return;
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);
    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    createMutate(
      {
        prompt: {
          name,
          template: editor.serialize(),
          template_structure: templateStructure,
          ...(metadata && { metadata: safelyParseJSON(metadata) }),
          ...(description && { description }),
          project_id: activeProjectId ?? undefined,
        },
      },
      {
        onSuccess: (prompt) => {
          onPromptCreated(prompt);
          setOpen(false);
        },
      },
    );
  }, [
    isValid,
    isCreating,
    metadata,
    editor,
    createMutate,
    name,
    templateStructure,
    description,
    activeProjectId,
    onPromptCreated,
    setOpen,
    setShowInvalidJSON,
  ]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        handleCreate();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, handleCreate]);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[720px]"
        header={<SheetTopBar variant="form" title={title} />}
        // Creating a prompt requires several distinct inputs — block
        // outside-click closing so a stray click never discards the form.
        blockOverlayClose
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6">
          <div className="space-y-1.5">
            <Label htmlFor="promptName">Name</Label>
            <Input
              id="promptName"
              dimension="sm"
              placeholder={title}
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="comet-body-s"
            />
          </div>

          <PromptTemplateEditor
            editor={editor}
            textMinHeightClassName="min-h-[120px]"
          />

          <PromptMetadataEditor
            value={metadata}
            onChange={setMetadata}
            showInvalidJSON={showInvalidJSON}
          />

          <div className="space-y-1.5">
            <Label htmlFor="promptDescription">Description</Label>
            <AutoGrowTextarea
              id="promptDescription"
              dimension="sm"
              className="comet-body-s"
              value={description}
              onChange={setDescription}
              placeholder="Add optional description"
            />
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setOpen(false)}
            disabled={isCreating}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={!isValid || isCreating}
            onClick={handleCreate}
          >
            {isCreating ? "Creating…" : "Create prompt"}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default CreatePromptSheet;
