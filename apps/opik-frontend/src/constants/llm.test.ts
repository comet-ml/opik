import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import { OPENAI_MODEL_CAPABILITIES } from "./llm";

// Not import.meta.url: under happy-dom vitest serves modules from an http origin, so that URL has no file path.
const SYNC_SCRIPT = resolve(
  __dirname,
  "../../../../scripts/sync_provider_models.py",
);

const readSyncScriptNonReasoningModels = (): string[] => {
  const source = readFileSync(SYNC_SCRIPT, "utf8");
  const match = source.match(/^OPENAI_NON_REASONING_MODELS = \{([^}]*)\}/m);
  if (!match) {
    throw new Error(`OPENAI_NON_REASONING_MODELS not found in ${SYNC_SCRIPT}`);
  }
  return [...match[1].matchAll(/"([^"]+)"/g)].map(([, id]) => id).sort();
};

describe("OPENAI_MODEL_CAPABILITIES", () => {
  it("pins the same models non-reasoning as the model sync script", () => {
    const pinnedNonReasoning = Object.entries(OPENAI_MODEL_CAPABILITIES)
      .filter(([, row]) => row.reasoning === false)
      .map(([model]) => model)
      .sort();

    expect(readSyncScriptNonReasoningModels()).toEqual(pinnedNonReasoning);
  });
});
