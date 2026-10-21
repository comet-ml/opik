import { LLMMessageFormatImplementation } from "../../types";
import { detectOpenInferenceFormat } from "./detector";
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
};

export {
  combineOpenInferenceMessages,
  detectOpenInferenceFormat,
  mapOpenInferenceMessages,
};
