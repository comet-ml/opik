import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";

import { BlueprintValue } from "@/types/agent-configs";
import { LLMMessage } from "@/types/llm";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import PromptTemplateView from "@/v2/pages-shared/llm/PromptTemplateView/PromptTemplateView";
import TextPromptEditor from "@/v2/pages-shared/TextPromptEditor/TextPromptEditor";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import Loader from "@/shared/Loader/Loader";
import {
  generateDefaultLLMPromptMessage,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";

export interface BlueprintValuePromptHandle {
  saveVersion: () => Promise<{ key: string; commit: string } | null>;
  validate: () => string | null;
  getCurrentTemplate: () => string;
}

type BlueprintValuePromptProps = {
  value: BlueprintValue;
  projectId?: string;
  isEditing?: boolean;
  onDirtyChange?: (isDirty: boolean) => void;
};

const BlueprintValuePrompt = forwardRef<
  BlueprintValuePromptHandle,
  BlueprintValuePromptProps
>(({ value, projectId, isEditing = false, onDirtyChange }, ref) => {
  const [draftTemplate, setDraftTemplate] = useState("");
  const [draftMessages, setDraftMessages] = useState<LLMMessage[]>([]);
  const initialTemplate = useRef("");

  const { data: prompt, isPending } = usePromptByCommit(
    { commitId: value.value },
    { enabled: !!value.value },
  );

  const { mutateAsync: createVersion } = useCreatePromptVersionMutation();

  const promptVersion = prompt?.requested_version;
  const isChatPrompt =
    prompt?.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  useEffect(() => {
    if (promptVersion && !initialTemplate.current) {
      if (isChatPrompt) {
        const messages = parseChatTemplateToLLMMessages(promptVersion.template);
        initialTemplate.current = JSON.stringify(
          messages.map((m) => ({ role: m.role, content: m.content })),
          null,
          2,
        );
        setDraftMessages(messages);
      } else {
        initialTemplate.current = promptVersion.template;
        setDraftTemplate(promptVersion.template);
      }
    }
  }, [promptVersion, isChatPrompt]);

  const onDirtyChangeRef = useRef(onDirtyChange);
  onDirtyChangeRef.current = onDirtyChange;

  useEffect(() => {
    if (!onDirtyChangeRef.current || !initialTemplate.current) return;
    const currentTemplate = isChatPrompt
      ? JSON.stringify(
          draftMessages.map((m) => ({ role: m.role, content: m.content })),
          null,
          2,
        )
      : draftTemplate;
    onDirtyChangeRef.current(currentTemplate !== initialTemplate.current);
  }, [draftTemplate, draftMessages, isChatPrompt]);

  const handleAddMessage = useCallback(() => {
    setDraftMessages((prev) => [...prev, generateDefaultLLMPromptMessage()]);
  }, []);

  useImperativeHandle(
    ref,
    () => ({
      getCurrentTemplate: () => {
        return isChatPrompt
          ? JSON.stringify(
              draftMessages.map((m) => ({
                role: m.role,
                content: m.content,
              })),
              null,
              2,
            )
          : draftTemplate;
      },
      validate: () => {
        if (isChatPrompt) {
          const hasEmpty = draftMessages.some((m) => {
            if (typeof m.content === "string") return !m.content.trim();
            if (Array.isArray(m.content)) {
              return m.content.every(
                (part) => part.type === "text" && !part.text.trim(),
              );
            }
            return true;
          });
          if (hasEmpty) return "Messages must not be empty";
        } else {
          if (!draftTemplate.trim()) return "Prompt must not be empty";
        }
        return null;
      },
      saveVersion: async () => {
        if (!prompt) return null;

        const currentTemplate = isChatPrompt
          ? JSON.stringify(
              draftMessages.map((m) => ({
                role: m.role,
                content: m.content,
              })),
              null,
              2,
            )
          : draftTemplate;

        if (currentTemplate === initialTemplate.current) return null;

        const data = await createVersion({
          name: prompt.name,
          template: currentTemplate,
          type: promptVersion?.type,
          templateStructure: prompt.template_structure,
          ...(projectId && {
            excludeBlueprintUpdateForProjects: [projectId],
          }),
          onSuccess: () => {},
        });

        return { key: value.key, commit: data.commit };
      },
    }),
    [
      createVersion,
      draftMessages,
      draftTemplate,
      isChatPrompt,
      prompt,
      promptVersion,
      projectId,
      value.key,
    ],
  );

  if (isPending) return <Loader />;

  if (isEditing) {
    return (
      <div className="flex flex-col gap-2">
        {isChatPrompt ? (
          <LLMPromptMessages
            messages={draftMessages}
            onChange={setDraftMessages}
            onAddMessage={handleAddMessage}
            hidePromptActions
            disableMedia
          />
        ) : (
          <TextPromptEditor
            value={draftTemplate}
            onChange={setDraftTemplate}
            label="Template"
            showDescription={false}
            labelClassName="comet-body-xs-accented mt-auto"
          />
        )}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {promptVersion && (
        <PromptTemplateView
          template={promptVersion.template}
          templateStructure={prompt?.template_structure}
          truncate
          labelClassName="comet-body-xs-accented mt-auto"
        />
      )}
    </div>
  );
});

BlueprintValuePrompt.displayName = "BlueprintValuePrompt";

export default BlueprintValuePrompt;
