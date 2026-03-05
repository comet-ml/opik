import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import { FileTerminal } from "lucide-react";

import { BlueprintValue } from "@/types/agent-configs";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import usePromptById from "@/api/prompts/usePromptById";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import PromptTemplateView from "@/components/pages-shared/llm/PromptTemplateView/PromptTemplateView";
import TextPromptEditor from "@/components/pages-shared/TextPromptEditor/TextPromptEditor";
import Loader from "@/components/shared/Loader/Loader";

export interface BlueprintValuePromptHandle {
  saveVersion: () => Promise<void>;
}

type BlueprintValuePromptProps = {
  value: BlueprintValue;
  isEditing?: boolean;
};

const BlueprintValuePrompt = forwardRef<
  BlueprintValuePromptHandle,
  BlueprintValuePromptProps
>(({ value, isEditing = false }, ref) => {
  const [draftTemplate, setDraftTemplate] = useState("");
  const initialTemplate = useRef("");

  const { data: promptVersion, isPending } = usePromptVersionById(
    { versionId: value.value },
    { enabled: !!value.value },
  );

  const { data: prompt } = usePromptById(
    { promptId: promptVersion?.prompt_id ?? "" },
    { enabled: !!promptVersion?.prompt_id },
  );

  const { mutateAsync: createVersion } = useCreatePromptVersionMutation();

  useEffect(() => {
    if (promptVersion && !initialTemplate.current) {
      initialTemplate.current = promptVersion.template;
      setDraftTemplate(promptVersion.template);
    }
  }, [promptVersion]);

  useImperativeHandle(
    ref,
    () => ({
      saveVersion: async () => {
        if (!prompt || draftTemplate === initialTemplate.current) return;
        await createVersion({
          name: prompt.name,
          template: draftTemplate,
          type: promptVersion?.type,
          templateStructure: prompt.template_structure,
          onSuccess: () => {},
        });
      },
    }),
    [createVersion, draftTemplate, prompt, promptVersion],
  );

  const header = (
    <div className="flex items-center gap-1.5 overflow-hidden">
      <FileTerminal className="size-3.5 shrink-0 text-muted-slate" />
      <span className="comet-body-s truncate text-muted-slate">
        {value.value}
      </span>
    </div>
  );

  if (isPending) return <Loader />;

  if (isEditing) {
    return (
      <div className="flex flex-col gap-2">
        {header}
        <TextPromptEditor
          value={draftTemplate}
          onChange={setDraftTemplate}
          label="Template"
          showDescription={false}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {header}
      {promptVersion && (
        <PromptTemplateView template={promptVersion.template} />
      )}
    </div>
  );
});

BlueprintValuePrompt.displayName = "BlueprintValuePrompt";

export default BlueprintValuePrompt;
