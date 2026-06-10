import {
  PlaygroundPromptType,
  PromptLibraryMetadata,
} from "@/types/playground";
import { PROMPT_TEMPLATE_STRUCTURE, PromptVersion } from "@/types/prompts";

export interface PromptVersionRef {
  id: string;
  promptId: string;
}

/**
 * Single source of truth for resolving which Prompt Library version(s) a
 * playground prompt is linked to. Used by BOTH the experiment-execute path
 * (test suites) and the experiment-log path (datasets / direct runs) so the
 * two can never drift apart again (the drift was the root cause of OPIK-6838).
 *
 * Collects, de-duplicated, in priority order:
 *  - the CHAT prompt version the prompt was loaded from (loadedChatPromptVersionId)
 *  - any per-message TEXT prompt versions (msg.promptVersionId)
 */
export const collectPromptVersionRefs = (
  prompt: PlaygroundPromptType,
): PromptVersionRef[] => {
  const refs: PromptVersionRef[] = [];
  const seen = new Set<string>();

  const add = (versionId?: string, promptId?: string) => {
    if (!versionId || !promptId || seen.has(versionId)) return;
    seen.add(versionId);
    refs.push({ id: versionId, promptId });
  };

  add(prompt.loadedChatPromptVersionId, prompt.loadedChatPromptId);
  prompt.messages.forEach((msg) => add(msg.promptVersionId, msg.promptId));

  return refs;
};

const parseTemplateJson = (template?: string): unknown => {
  if (!template) return null;
  try {
    return JSON.parse(template);
  } catch {
    return template;
  }
};

interface VersionData {
  id: string;
  template?: string;
  commit?: string;
  metadata?: object;
}

/**
 * IO-free builder for the `opik_prompts` trace-metadata entry. Kept separate
 * from the fetching hook so the transform (and the `modified` flag) is unit
 * testable. `modified` is true when the current playground prompt has diverged
 * from the library version it was loaded from.
 */
export const buildPromptLibraryMetadata = (
  promptData: { name: string; id: string; template_structure?: string },
  version: VersionData,
  modified: boolean,
): PromptLibraryMetadata => ({
  name: promptData.name,
  id: promptData.id,
  template_structure:
    (promptData.template_structure as PROMPT_TEMPLATE_STRUCTURE) ??
    PROMPT_TEMPLATE_STRUCTURE.TEXT,
  modified,
  version: {
    template: parseTemplateJson(version.template),
    id: version.id,
    ...(version.commit && { commit: version.commit }),
    ...(version.metadata && { metadata: version.metadata }),
  },
});

type FetchPromptVersion = (params: {
  versionId: string;
}) => Promise<PromptVersion>;

/**
 * Resolve which Prompt Library version a loaded prompt should link to. Shared
 * by the CHAT and TEXT paths in useHydratePromptMetadata so the resolution
 * rules can't drift between them. Anchors to the explicitly-loaded version when
 * present, otherwise the prompt's latest version. Returns undefined when there
 * is no safe anchor — either no version exists, or an explicit version was
 * requested but could not be fetched (in which case we must not silently
 * compare against the wrong version).
 */
export const resolvePromptVersionForLink = async (
  promptData: { latest_version?: PromptVersion } | undefined,
  explicitVersionId: string | undefined,
  fetchPromptVersion: FetchPromptVersion,
): Promise<PromptVersion | undefined> => {
  // No explicit version requested: the prompt's embedded latest_version already
  // satisfies the link, so avoid an extra network round-trip (matches the
  // pre-refactor behavior). Resolves to undefined when there is no version.
  if (!explicitVersionId) return promptData?.latest_version;

  // An explicit version was requested. Fetch it; if it can't be fetched
  // (deleted, or a transient error — we intentionally don't distinguish), bail
  // rather than silently anchor to the wrong version.
  try {
    return await fetchPromptVersion({ versionId: explicitVersionId });
  } catch {
    return undefined;
  }
};
