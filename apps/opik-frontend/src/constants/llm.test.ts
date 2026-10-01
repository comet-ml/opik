import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import { PROVIDER_MODELS } from "@/constants/providerModels";
import { supportsSamplingParams } from "@/lib/modelUtils";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { ANTHROPIC_MODEL_CAPABILITIES, OPENAI_MODEL_CAPABILITIES } from "./llm";

// Not import.meta.url: under happy-dom vitest serves modules from an http origin, so that URL has no file path.
const SYNC_SCRIPT = resolve(
  __dirname,
  "../../../../scripts/sync_provider_models.py",
);

const ANTHROPIC_MODEL_NAME_JAVA = resolve(
  __dirname,
  "../../../opik-backend/src/main/java/com/comet/opik/infrastructure/llm/antropic/AnthropicModelName.java",
);

const readSyncScriptNonReasoningModels = (): string[] => {
  const source = readFileSync(SYNC_SCRIPT, "utf8");
  const match = source.match(/^OPENAI_NON_REASONING_MODELS = \{([^}]*)\}/m);
  if (!match) {
    throw new Error(`OPENAI_NON_REASONING_MODELS not found in ${SYNC_SCRIPT}`);
  }
  return [...match[1].matchAll(/"([^"]+)"/g)].map(([, id]) => id).sort();
};

const readBackendAnthropicModels = () => {
  const source = readFileSync(ANTHROPIC_MODEL_NAME_JAVA, "utf8");
  const idByConstant = new Map(
    [...source.matchAll(/^\s+(\w+)\(\s*"([^"]+)"\s*\)/gm)].map(
      ([, constant, id]) => [constant, id],
    ),
  );
  const capableEntries = source.match(
    /SAMPLING_CAPABLE_MODEL_IDS = Set\.of\(([^)]*)\)/,
  )?.[1];
  if (idByConstant.size === 0 || capableEntries === undefined) {
    throw new Error(
      `Enum constants or SAMPLING_CAPABLE_MODEL_IDS not found in ${ANTHROPIC_MODEL_NAME_JAVA}`,
    );
  }

  const samplingCapable = capableEntries
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const id = idByConstant.get(entry.replace(/\.value$/, ""));
      if (!id) {
        throw new Error(
          `SAMPLING_CAPABLE_MODEL_IDS entry ${entry} is not an AnthropicModelName constant's .value`,
        );
      }
      return id;
    });

  return {
    modelIds: [...idByConstant.values()],
    samplingCapable: new Set(samplingCapable),
  };
};

const BACKEND_ANTHROPIC_MODELS = readBackendAnthropicModels();

describe("OPENAI_MODEL_CAPABILITIES", () => {
  it("pins the same models non-reasoning as the model sync script", () => {
    const pinnedNonReasoning = Object.entries(OPENAI_MODEL_CAPABILITIES)
      .filter(([, row]) => row.reasoning === false)
      .map(([model]) => model)
      .sort();

    expect(readSyncScriptNonReasoningModels()).toEqual(pinnedNonReasoning);
  });
});

describe("ANTHROPIC_MODEL_CAPABILITIES", () => {
  it("names the same sampling-capable models as the backend's AnthropicModelName", () => {
    const samplingCapable = Object.entries(ANTHROPIC_MODEL_CAPABILITIES)
      .filter(([, row]) => row?.supportsSamplingParams)
      .map(([model]) => model)
      .sort();

    expect(
      samplingCapable,
      "rows with supportsSamplingParams: true vs AnthropicModelName.SAMPLING_CAPABLE_MODEL_IDS",
    ).toEqual([...BACKEND_ANTHROPIC_MODELS.samplingCapable].sort());
  });

  it("knows no Claude model that the backend's AnthropicModelName lacks", () => {
    const frontendModels = new Set([
      ...Object.keys(ANTHROPIC_MODEL_CAPABILITIES),
      ...(PROVIDER_MODELS[PROVIDER_TYPE.ANTHROPIC] ?? []).map(
        ({ value }) => value as string,
      ),
    ]);

    expect(
      [...frontendModels].filter(
        (id) => !BACKEND_ANTHROPIC_MODELS.modelIds.includes(id),
      ),
      "Claude ids the frontend resolves against but the backend enum does not list",
    ).toEqual([]);
  });

  it.each(BACKEND_ANTHROPIC_MODELS.modelIds)(
    "classifies %s the way the backend does",
    (id) => {
      const backendTakesSamplingParams =
        BACKEND_ANTHROPIC_MODELS.samplingCapable.has(id);

      // The prefixed spelling misses the row lookup, so it is what tests the frontend's id resolution
      // against the backend's rule that a listed id always reads as itself, however it is routed.
      for (const model of [id, `anthropic/${id}`]) {
        expect(
          supportsSamplingParams(model as PROVIDER_MODEL_TYPE),
          `supportsSamplingParams("${model}")`,
        ).toBe(backendTakesSamplingParams);
      }
    },
  );
});
