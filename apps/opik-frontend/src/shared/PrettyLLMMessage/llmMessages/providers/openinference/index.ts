import { LLMMessageFormatImplementation } from "../../types";
import {
  detectOpenInferenceFormat,
  resolveOpenInferenceFormatHint,
} from "./detector";
import { mapOpenInferenceMessagePair } from "./pairMapper";
import {
  combineOpenInferenceMessages,
  mapOpenInferenceMessages,
} from "./mapper";

export const openinferenceFormat: LLMMessageFormatImplementation = {
  name: "openinference",
  detector: detectOpenInferenceFormat,
  mapper: mapOpenInferenceMessages,
  combiner: combineOpenInferenceMessages,
  pairMapper: mapOpenInferenceMessagePair,
  resolveHint: resolveOpenInferenceFormatHint,
};

export {
  combineOpenInferenceMessages,
  detectOpenInferenceFormat,
  mapOpenInferenceMessages,
};
