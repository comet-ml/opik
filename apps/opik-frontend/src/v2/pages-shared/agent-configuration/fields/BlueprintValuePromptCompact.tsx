import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";

import { BlueprintValue } from "@/types/agent-configs";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import Loader from "@/shared/Loader/Loader";
import {
  generateDefaultLLMPromptMessage,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";
import { BlueprintValuePromptHandle } from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintValuePrompt";
import AutoResizeTextarea from "./AutoResizeTextarea";
import BlueprintChatMessages from "./BlueprintChatMessages";
import CollapsibleBlock from "./CollapsibleBlock";
import { FieldsCollapseController } from "./useFieldsCollapse";

type BlueprintValuePromptCompactProps = {
  value: BlueprintValue;
  projectId?: string;
  isEditing?: boolean;
  onDirtyChange?: (isDirty: boolean) => void;
  tone?: "muted" | "white";
  controller?: FieldsCollapseController;
};

const messagesToTemplate = (messages: LLMMessage[]): string =>
  JSON.stringify(
    messages.map((m) => ({ role: m.role, content: m.content })),
    null,
    2,
  );

const BlueprintValuePromptCompact = forwardRef<
  BlueprintValuePromptHandle,
  BlueprintValuePromptCompactProps
>(
  (
    { value, projectId, isEditing = false, onDirtyChange, tone, controller },
    ref,
  ) => {
    const [draftTemplate, setDraftTemplate] = useState("");
    const [draftMessages, setDraftMessages] = useState<LLMMessage[]>([]);
    const [expandedMessageIndexes, setExpandedMessageIndexes] = useState<
      Set<number>
    >(new Set());
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
          const messages = parseChatTemplateToLLMMessages(
            promptVersion.template,
          );
          initialTemplate.current = messagesToTemplate(messages);
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
        ? messagesToTemplate(draftMessages)
        : draftTemplate;
      onDirtyChangeRef.current(currentTemplate !== initialTemplate.current);
    }, [draftTemplate, draftMessages, isChatPrompt]);

    const handleChangeMessage = (index: number, newText: string) => {
      setDraftMessages((prev) => {
        const next = [...prev];
        const existing = next[index].content;
        if (Array.isArray(existing)) {
          const updated = existing.map((part) =>
            part.type === "text" ? { ...part, text: newText } : part,
          );
          next[index] = { ...next[index], content: updated };
        } else {
          next[index] = { ...next[index], content: newText };
        }
        return next;
      });
    };

    const handleAddMessage = () => {
      setDraftMessages((prev) => [...prev, generateDefaultLLMPromptMessage()]);
    };

    const handleDeleteMessage = (index: number) => {
      setDraftMessages((prev) => prev.filter((_, i) => i !== index));
    };

    const handleDuplicateMessage = (index: number) => {
      setDraftMessages((prev) => {
        const clone = generateDefaultLLMPromptMessage({
          role: prev[index].role,
          content: prev[index].content,
        });
        const next = [...prev];
        next.splice(index + 1, 0, clone);
        return next;
      });
    };

    const handleChangeRole = (index: number, role: LLM_MESSAGE_ROLE) => {
      setDraftMessages((prev) => {
        const next = [...prev];
        next[index] = { ...next[index], role };
        return next;
      });
    };

    const handleReorder = (reordered: LLMMessage[]) => {
      setDraftMessages(reordered);
    };

    const messagesForRead = useMemo<LLMMessage[]>(() => {
      if (!promptVersion) return [];
      if (isChatPrompt) {
        return parseChatTemplateToLLMMessages(promptVersion.template);
      }
      return [
        {
          id: "text",
          role: LLM_MESSAGE_ROLE.user,
          content: promptVersion.template,
        },
      ];
    }, [promptVersion, isChatPrompt]);

    const messageCount = isChatPrompt
      ? (isEditing ? draftMessages : messagesForRead).length
      : 1;
    const broadcastVersion = controller?.broadcast.version ?? 0;
    const broadcastAction = controller?.broadcast.action ?? null;

    useEffect(() => {
      if (broadcastAction === "expand") {
        setExpandedMessageIndexes(
          new Set(Array.from({ length: messageCount }, (_, i) => i)),
        );
      } else if (broadcastAction === "collapse") {
        setExpandedMessageIndexes(new Set());
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [broadcastVersion]);

    useImperativeHandle(
      ref,
      () => ({
        getCurrentTemplate: () =>
          isChatPrompt ? messagesToTemplate(draftMessages) : draftTemplate,
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
            ? messagesToTemplate(draftMessages)
            : draftTemplate;
          if (currentTemplate === initialTemplate.current) return null;
          const data = await createVersion({
            name: prompt.name,
            template: currentTemplate,
            type: promptVersion?.type,
            templateStructure: prompt.template_structure,
            projectId,
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

    const toggleMessage = (index: number) =>
      setExpandedMessageIndexes((prev) => {
        const next = new Set(prev);
        if (next.has(index)) next.delete(index);
        else next.add(index);
        return next;
      });

    if (isChatPrompt) {
      return (
        <BlueprintChatMessages
          messages={isEditing ? draftMessages : messagesForRead}
          isExpanded={(i) => expandedMessageIndexes.has(i)}
          onToggle={toggleMessage}
          editable={isEditing}
          onChangeMessage={handleChangeMessage}
          onChangeRole={isEditing ? handleChangeRole : undefined}
          onAddMessage={isEditing ? handleAddMessage : undefined}
          onDeleteMessage={isEditing ? handleDeleteMessage : undefined}
          onDuplicateMessage={isEditing ? handleDuplicateMessage : undefined}
          onReorder={isEditing ? handleReorder : undefined}
          tone={tone}
        />
      );
    }

    // Text prompt — single collapsible
    return (
      <CollapsibleBlock
        collapsible
        expanded={expandedMessageIndexes.has(0)}
        onToggle={() => toggleMessage(0)}
        label="Prompt"
        tone={tone}
      >
        {isEditing ? (
          <AutoResizeTextarea
            value={draftTemplate}
            onChange={setDraftTemplate}
          />
        ) : (
          <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
            {draftTemplate || promptVersion?.template}
          </div>
        )}
      </CollapsibleBlock>
    );
  },
);

BlueprintValuePromptCompact.displayName = "BlueprintValuePromptCompact";

export default BlueprintValuePromptCompact;
