#!/usr/bin/env node
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

type CapabilityEntry = {
  supports_vision?: boolean;
  supports_audio_input?: boolean;
  supports_audio_output?: boolean;
  supports_function_calling?: boolean;
  supports_parallel_function_calling?: boolean;
  supports_prompt_caching?: boolean;
  supports_reasoning?: boolean;
  supports_response_schema?: boolean;
  supports_system_messages?: boolean;
  supports_web_search?: boolean;
  litellm_provider?: string;
  [key: string]: unknown;
};

type PricingJson = Record<string, CapabilityEntry>;

type ManifestEntry = {
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

type Manifest = {
  metadata: {
    generatedAt: string;
    source: string;
    totalModels: number;
  };
  models: Record<string, ManifestEntry>;
};

type CapabilityFlagKey = Exclude<keyof ManifestEntry, "litellmProvider">;

const CAPABILITY_FIELDS = [
  "supports_vision",
  "supports_audio_input",
  "supports_audio_output",
  "supports_function_calling",
  "supports_parallel_function_calling",
  "supports_prompt_caching",
  "supports_reasoning",
  "supports_response_schema",
  "supports_system_messages",
  "supports_web_search",
] as const;

const normalizeKey = (field: (typeof CAPABILITY_FIELDS)[number]) =>
  field.replace(/_([a-z])/g, (_, char: string) => char.toUpperCase());

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const sdkRoot = path.resolve(__dirname, "..", "..");

const DEST_PATH = path.join(
  sdkRoot,
  "src",
  "opik",
  "evaluation",
  "models",
  "data",
  "modelCapabilities.generated.json"
);

const SOURCE_RELPATH = [
  "apps",
  "opik-backend",
  "src",
  "main",
  "resources",
  "model_prices_and_context_window.json",
] as const;

const candidateRoots = [
  process.cwd(),
  path.resolve(process.cwd(), ".."),
  path.resolve(process.cwd(), "..", ".."),
  path.resolve(__dirname, ".."),
  path.resolve(__dirname, "..", ".."),
  path.resolve(__dirname, "..", "..", ".."),
  path.resolve(__dirname, "..", "..", "..", ".."),
];

const SOURCE_PATH = (() => {
  for (const rootCandidate of candidateRoots) {
    const candidate = path.join(rootCandidate, ...SOURCE_RELPATH);
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  throw new Error(
    `Source pricing file not found. Ensure you run this script from the repository or sdk root.\n` +
      `Checked candidates:\n${candidateRoots
        .map((root) => path.join(root, ...SOURCE_RELPATH))
        .join("\n")}`
  );
})();

function loadSource(): PricingJson {
  const raw = fs.readFileSync(SOURCE_PATH, "utf8");
  return JSON.parse(raw) as PricingJson;
}

function buildManifest(data: PricingJson): Manifest {
  const models: Record<string, ManifestEntry> = {};
  let totalModels = 0;

  for (const [modelName, entry] of Object.entries(data)) {
    if (modelName === "sample_spec") {
      continue;
    }

    if (!entry || typeof entry !== "object") {
      continue;
    }

    const manifestEntry: ManifestEntry = {};

    for (const field of CAPABILITY_FIELDS) {
      if (field in entry) {
        const capabilityKey = normalizeKey(field) as CapabilityFlagKey;
        manifestEntry[capabilityKey] = Boolean(entry[field]);
      }
    }

    if ("litellm_provider" in entry && typeof entry.litellm_provider === "string") {
      manifestEntry.litellmProvider = entry.litellm_provider;
    }

    models[modelName] = manifestEntry;
    totalModels += 1;
  }

  return {
    metadata: {
      generatedAt: new Date().toISOString(),
      source: path.relative(path.dirname(DEST_PATH), SOURCE_PATH),
      totalModels,
    },
    models,
  };
}

function writeManifest(manifest: Manifest): void {
  fs.mkdirSync(path.dirname(DEST_PATH), { recursive: true });
  fs.writeFileSync(DEST_PATH, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(
    `Updated model capabilities manifest at ${DEST_PATH} (${manifest.metadata.totalModels} models)`
  );
}

function main(): void {
  const source = loadSource();
  const manifest = buildManifest(source);
  writeManifest(manifest);
}

main();
