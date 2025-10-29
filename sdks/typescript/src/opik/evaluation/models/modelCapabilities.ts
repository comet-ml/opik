import rawManifest from "./data/modelCapabilities.generated.json";

type CapabilityEntry = {
  supportsVision?: boolean;
  supportsAudioInput?: boolean;
  supportsAudioOutput?: boolean;
  supportsFunctionCalling?: boolean;
  supportsParallelFunctionCalling?: boolean;
  supportsPromptCaching?: boolean;
  supportsReasoning?: boolean;
  supportsResponseSchema?: boolean;
  supportsSystemMessages?: boolean;
  supportsWebSearch?: boolean;
  litellmProvider?: string;
};

type CapabilityManifest = {
  metadata: {
    generatedAt: string;
    source: string;
    totalModels: number;
  };
  models: Record<string, CapabilityEntry>;
};

const manifest = rawManifest as CapabilityManifest;

const DEFAULT_VISION_MODELS = [
  // OpenAI
  "gpt-4-vision",
  "gpt-4o",
  "gpt-4o-mini",
  "gpt-4-turbo",
  "chatgpt-4o-latest",
  "gpt-5-mini",
  // Anthropic
  "claude-3",
  "claude-3-5",
  // Google
  "gemini-1.5-pro",
  "gemini-1.5-flash",
  "gemini-pro-vision",
  "gemini-2.0-flash",
  // Meta
  "llama-3.2-11b-vision",
  "llama-3.2-90b-vision",
  // Mistral
  "pixtral",
  // Misc
  "qwen-vl",
  "qwen2-vl",
  "phi-3-vision",
  "phi-3.5-vision",
  "llava",
  "cogvlm",
  "yi-vl",
];

const VISION_MODEL_PATTERNS = [
  /qwen.*vl/i, // Qwen VL models (e.g., qwen/qwen-vl-plus, qwen2.5-vl-32b-instruct)
];

const normalizeModelName = (value: string) => value.trim().toLowerCase();

const candidateKeys = (modelName: string): string[] => {
  const normalized = normalizeModelName(modelName);
  const candidates = new Set<string>([normalized]);

  const slashIndex = normalized.lastIndexOf("/") + 1;
  if (slashIndex > 0 && slashIndex < normalized.length) {
    candidates.add(normalized.slice(slashIndex));
  }

  const colonIndex = normalized.indexOf(":");
  if (colonIndex > 0) {
    candidates.add(normalized.slice(0, colonIndex));

    if (slashIndex > 0 && slashIndex < colonIndex) {
      candidates.add(normalized.slice(slashIndex, colonIndex));
    }

    if (colonIndex + 1 < normalized.length) {
      candidates.add(normalized.slice(colonIndex + 1));
    }
  }

  return Array.from(candidates);
};

const matchesVisionPattern = (modelName: string): boolean =>
  VISION_MODEL_PATTERNS.some((pattern) => pattern.test(modelName));

const exactCapabilities = new Map<string, CapabilityEntry>(
  Object.entries(manifest.models)
);

const normalizedCapabilities = new Map<string, CapabilityEntry>();
exactCapabilities.forEach((entry, name) => {
  normalizedCapabilities.set(normalizeModelName(name), entry);
});

const defaultVisionModels = new Set(
  DEFAULT_VISION_MODELS.map((model) => normalizeModelName(model))
);

const customVisionModels = new Set<string>();

const isModelInSet = (
  modelName: string,
  set: Set<string>,
  matchPrefixes = false
): boolean => {
  const candidates = candidateKeys(modelName);

  for (const candidate of candidates) {
    if (set.has(candidate)) {
      return true;
    }

    if (matchPrefixes) {
      for (const value of set) {
        if (candidate.startsWith(value)) {
          return true;
        }
      }
    }
  }
  return false;
};

export type ModelCapability = CapabilityEntry & {
  modelName: string;
};

export class ModelCapabilities {
  private static getCapability(modelName: string): CapabilityEntry | undefined {
    const direct = exactCapabilities.get(modelName);
    if (direct) {
      return direct;
    }

    for (const key of candidateKeys(modelName)) {
      const entry = normalizedCapabilities.get(key);
      if (entry) {
        return entry;
      }
    }

    return undefined;
  }

  static supportsVision(modelName?: string | null): boolean {
    if (!modelName) {
      return false;
    }

    if (matchesVisionPattern(modelName)) {
      return true;
    }

    const capability = this.getCapability(modelName);
    if (capability?.supportsVision !== undefined) {
      return capability.supportsVision;
    }

    if (isModelInSet(modelName, customVisionModels)) {
      return true;
    }

    if (isModelInSet(modelName, defaultVisionModels, true)) {
      return true;
    }

    return false;
  }

  static addVisionModel(modelName: string): void {
    if (modelName) {
      customVisionModels.add(normalizeModelName(modelName));
    }
  }

  static getModelCapabilities(
    modelName?: string | null
  ): ModelCapability | undefined {
    if (!modelName) {
      return undefined;
    }

    const capability = this.getCapability(modelName);

    if (!capability) {
      return undefined;
    }

    return {
      modelName,
      ...capability,
    };
  }
}
