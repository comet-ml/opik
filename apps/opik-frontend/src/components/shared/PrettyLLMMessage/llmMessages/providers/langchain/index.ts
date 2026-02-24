import { LLMMessageFormatImplementation } from "../../types";
import { detectLangChainFormat } from "./detector";
import { mapLangChainMessages, combineLangChainMessages } from "./mapper";

export const langchainFormat: LLMMessageFormatImplementation = {
  name: "langchain",
  detector: detectLangChainFormat,
  mapper: mapLangChainMessages,
  combiner: combineLangChainMessages,
};

export {
  detectLangChainFormat,
  mapLangChainMessages,
  combineLangChainMessages,
};
