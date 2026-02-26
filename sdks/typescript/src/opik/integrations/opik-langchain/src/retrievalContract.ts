import { Serialized } from "@langchain/core/load/serializable";

type JsonNode = Record<string, unknown>;

const OPIK_KIND_KEY = "opik.kind";
const OPIK_PROVIDER_KEY = "opik.provider";
const OPIK_OPERATION_KEY = "opik.operation";

const RETRIEVAL_KIND = "retrieval";
const DEFAULT_RETRIEVAL_OPERATION = "retrieve";

const GENERIC_RETRIEVER_IDS = new Set([
  "retriever",
  "base",
  "vectorstore",
  "vector_store",
  "langchain",
  "langchain_core",
  "langchain_community",
]);

const normalizeProvider = (value: unknown): string | undefined => {
  if (typeof value !== "string") {
    return undefined;
  }

  const normalized = value.trim().toLowerCase().replaceAll(" ", "_");
  if (!normalized || GENERIC_RETRIEVER_IDS.has(normalized)) {
    return undefined;
  }

  return normalized;
};

const inferProviderFromSerialized = (
  retriever: Serialized
): string | undefined => {
  const identifiers = retriever.id;
  if (!Array.isArray(identifiers)) {
    return undefined;
  }

  for (let i = identifiers.length - 1; i >= 0; i -= 1) {
    const provider = normalizeProvider(identifiers[i]);
    if (provider) {
      return provider;
    }
  }

  return undefined;
};

export const buildRetrieverMetadata = (
  retriever: Serialized,
  metadata?: JsonNode
): JsonNode => {
  const mergedMetadata: JsonNode = { ...(metadata ?? {}) };

  if (!(OPIK_KIND_KEY in mergedMetadata)) {
    mergedMetadata[OPIK_KIND_KEY] = RETRIEVAL_KIND;
  }
  if (!(OPIK_OPERATION_KEY in mergedMetadata)) {
    mergedMetadata[OPIK_OPERATION_KEY] = DEFAULT_RETRIEVAL_OPERATION;
  }

  const provider =
    normalizeProvider(mergedMetadata.ls_provider) ??
    inferProviderFromSerialized(retriever);

  if (provider && !(OPIK_PROVIDER_KEY in mergedMetadata)) {
    mergedMetadata[OPIK_PROVIDER_KEY] = provider;
  }

  return mergedMetadata;
};
