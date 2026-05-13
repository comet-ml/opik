import { getTrackContext } from "@/decorators/track";
import type { BasePrompt } from "./BasePrompt";
import type * as OpikApi from "@/rest_api/api";

interface PromptRef {
  name: string;
  commit?: string;
  prompt_id?: string;
}

function buildPayload(
  existingMetadata: OpikApi.JsonListString | undefined,
  ref: PromptRef
): Record<string, unknown> {
  const meta =
    existingMetadata && typeof existingMetadata === "object" && !Array.isArray(existingMetadata)
      ? (existingMetadata as Record<string, unknown>)
      : {};
  const existingRefs = (Array.isArray(meta._prompt_references) ? meta._prompt_references : []) as PromptRef[];
  return { _prompt_references: [...existingRefs, ref] };
}

export function injectPromptIntoTraceContext(p: BasePrompt): void {
  const ctx = getTrackContext();
  if (!ctx) return;

  const ref: PromptRef = { name: p.name };
  if (p.commit) ref.commit = p.commit;
  if (p.id) ref.prompt_id = p.id;

  const { trace, span } = ctx;

  trace.update({
    metadata: buildPayload(trace.data.metadata, ref),
  });

  span.update({
    metadata: buildPayload(span.data.metadata, ref),
  });
}
