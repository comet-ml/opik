import { useMemo, useRef, useState } from "react";
import useAppStore from "@/store/AppStore";
import usePromptsList from "@/api/prompts/usePromptsList";
import { ExtractedPromptData, OpenAIMessage } from "@/lib/prompt";
import { convertOptimizationVariableFormat } from "@/lib/optimizations";
import { PromptWithLatestVersion } from "@/types/prompts";

export const convertMessages = (messages: OpenAIMessage[]) =>
  messages.map((msg) => ({
    role: msg.role,
    content: convertOptimizationVariableFormat(msg.content),
  }));

interface UseSaveToPromptLibraryOptions {
  promptName: string;
  extractedPrompt: ExtractedPromptData | null;
  optimizationId: string;
  optimizationName: string;
  experimentId: string;
}

interface UseSaveToPromptLibraryReturn {
  canSaveToLibrary: boolean;
  saveDialogOpen: boolean;
  openSaveDialog: () => void;
  closeSaveDialog: () => void;
  dialogKey: number;
  existingPrompt: PromptWithLatestVersion | undefined;
  saveTemplate: string;
  saveMetadata: {
    created_from: string;
    optimization_id: string;
    optimization_name: string;
    experiment_id: string;
    prompt_type: string | undefined;
  };
}

export const useSaveToPromptLibrary = ({
  promptName,
  extractedPrompt,
  optimizationId,
  optimizationName,
  experimentId,
}: UseSaveToPromptLibraryOptions): UseSaveToPromptLibraryReturn => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  const dialogKeyRef = useRef(0);

  const { data: promptsData } = usePromptsList(
    {
      workspaceName,
      search: promptName,
      page: 1,
      size: 1,
    },
    {
      enabled: Boolean(promptName),
    },
  );

  const existingPrompt = useMemo((): PromptWithLatestVersion | undefined => {
    const prompts = promptsData?.content ?? [];
    return prompts.find((p) => p.name === promptName);
  }, [promptsData, promptName]);

  const canSaveToLibrary = useMemo(() => {
    if (!extractedPrompt) return false;

    if (extractedPrompt.type === "single") return true;

    // for multi-agent prompts, only allow saving if there's exactly one agent
    const agentNames = Object.keys(extractedPrompt.data);
    return agentNames.length === 1;
  }, [extractedPrompt]);

  const saveTemplate = useMemo(() => {
    if (!extractedPrompt || !canSaveToLibrary) return "";

    if (extractedPrompt.type === "single") {
      const convertedMessages = convertMessages(extractedPrompt.data);
      return JSON.stringify(convertedMessages, null, 2);
    }

    const agentNames = Object.keys(extractedPrompt.data);
    const convertedMessages = convertMessages(
      extractedPrompt.data[agentNames[0]],
    );
    return JSON.stringify(convertedMessages, null, 2);
  }, [extractedPrompt, canSaveToLibrary]);

  const saveMetadata = useMemo(
    () => ({
      created_from: "optimization_studio",
      optimization_id: optimizationId,
      optimization_name: optimizationName,
      experiment_id: experimentId,
      prompt_type: extractedPrompt?.type,
    }),
    [optimizationId, optimizationName, experimentId, extractedPrompt?.type],
  );

  const openSaveDialog = () => {
    dialogKeyRef.current += 1;
    setSaveDialogOpen(true);
  };

  const closeSaveDialog = () => {
    setSaveDialogOpen(false);
  };

  return {
    canSaveToLibrary,
    saveDialogOpen,
    openSaveDialog,
    closeSaveDialog,
    dialogKey: dialogKeyRef.current,
    existingPrompt,
    saveTemplate,
    saveMetadata,
  };
};
