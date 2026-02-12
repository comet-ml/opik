import React, { useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import { PromptLibraryMetadata } from "@/types/playground";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import get from "lodash/get";
import { FileTerminal, GitCommitVertical } from "lucide-react";
import useAppStore from "@/store/AppStore";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import TryInPlaygroundButton from "@/components/pages/PromptPage/TryInPlaygroundButton";
import PromptContentView, {
  CustomUseInPlaygroundButton,
} from "./PromptContentView";

// Helper to ensure template is always a string for PromptVersion
// The template from trace metadata can be either a string (legacy) or parsed JSON object (new format)
const normalizeTemplate = (template: unknown): string => {
  if (typeof template === "string") {
    return template;
  }
  if (template !== null && template !== undefined) {
    return JSON.stringify(template, null, 2);
  }
  return "";
};

type PromptsTabProps = {
  data: Trace | Span;
  search?: string;
};

const convertRawPromptToPromptWithLatestVersion = (
  rawPrompt: PromptLibraryMetadata,
): PromptWithLatestVersion => {
  const date = new Date().toISOString();

  // Use existing metadata if available, otherwise use empty object
  // Empty object causes isMessagesJsonFormat() to return false, so the template
  // is treated as plain text (correct for SDK text prompts without metadata)
  const metadata = rawPrompt.version.metadata ?? {};

  const promptVersion: PromptVersion = {
    id: rawPrompt.version.id,
    template: normalizeTemplate(rawPrompt.version.template),
    metadata,
    commit: rawPrompt.version.commit ?? "",
    prompt_id: rawPrompt.id,
    created_at: date, // We don't have this in raw data, using current time
  };

  return {
    id: rawPrompt.id,
    name: rawPrompt.name,
    description: "", // We don't have this in raw data
    last_updated_at: date, // We don't have this in raw data
    created_at: date, // We don't have this in raw data
    version_count: 1, // Assuming single version
    tags: [], // We don't have this in raw data
    template_structure: rawPrompt.template_structure,
    latest_version: promptVersion,
  };
};

const PromptsTab: React.FunctionComponent<PromptsTabProps> = ({
  data,
  search,
}) => {
  const rawPrompts = get(
    data.metadata as Record<string, unknown>,
    "opik_prompts",
    null,
  ) as PromptLibraryMetadata[] | null;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const showOptimizerPrompts = useIsFeatureEnabled(
    FeatureToggleKeys.OPTIMIZATION_STUDIO_ENABLED,
  );

  const prompts = useMemo(() => {
    if (!showOptimizerPrompts) return [];
    if (Array.isArray(rawPrompts) && rawPrompts.length > 0) {
      return (rawPrompts as PromptLibraryMetadata[]).map(
        convertRawPromptToPromptWithLatestVersion,
      );
    }
    return [];
  }, [rawPrompts, showOptimizerPrompts]);

  const renderPrompts = () => {
    if (!prompts || prompts.length === 0 || !rawPrompts) return null;

    return prompts.map((promptInfo: PromptWithLatestVersion, index: number) => {
      const promptName = promptInfo?.name || `Prompt ${index + 1}`;
      const rawTemplate = rawPrompts[index]?.version?.template;
      const commitHash = promptInfo?.latest_version?.commit;
      const promptId = promptInfo?.id;

      return (
        <AccordionItem key={index} value={`prompt-${index}`}>
          <AccordionTrigger>
            <div className="flex items-center gap-2">
              <FileTerminal className="size-4" />
              <div className="flex flex-col items-start">
                <div className="flex items-center gap-2">
                  <span>Prompt: {promptName}</span>
                  {commitHash && (
                    <div className="flex items-center">
                      <GitCommitVertical className="size-3 text-muted-slate" />
                      <span className="text-muted-slate">
                        {commitHash.substring(0, 8)}
                      </span>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </AccordionTrigger>
          <AccordionContent className="px-3">
            <PromptContentView
              template={rawTemplate ?? promptInfo?.latest_version?.template}
              promptId={promptId}
              activeVersionId={rawPrompts[index]?.version?.id}
              workspaceName={workspaceName}
              search={search}
              templateStructure={rawPrompts[index]?.template_structure}
              playgroundButton={
                <TryInPlaygroundButton
                  prompt={promptInfo}
                  ButtonComponent={CustomUseInPlaygroundButton}
                />
              }
            />
          </AccordionContent>
        </AccordionItem>
      );
    });
  };

  return (
    <Accordion type="multiple" className="w-full" defaultValue={["prompt-0"]}>
      {renderPrompts()}
    </Accordion>
  );
};

export default PromptsTab;
