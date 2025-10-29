/**
 * Models module for Opik evaluation system.
 *
 * Provides an abstraction layer for different LLM providers, allowing them to be
 * used interchangeably in evaluation tasks and metrics.
 *
 * @module models
 */

export { OpikBaseModel } from "./OpikBaseModel";
export type {
  OpikMessage,
  OpikSystemMessage,
  OpikUserMessage,
  OpikAssistantMessage,
  OpikToolMessage,
  ProviderOptions,
} from "./OpikBaseModel";
export { VercelAIChatModel } from "./VercelAIChatModel";
export { ModelCapabilities } from "./modelCapabilities";
export {
  createModel,
  createModelFromInstance,
  resolveModel,
} from "./modelsFactory";
export {
  ModelError,
  ModelGenerationError,
  ModelConfigurationError,
} from "./errors";
export { detectProvider } from "./providerDetection";
export type {
  SupportedModelId,
  AllProviderOptions,
  ProviderOptionsForModel,
  OpenAIProviderOptions,
  AnthropicProviderOptions,
  GoogleProviderOptions,
} from "./providerDetection";
