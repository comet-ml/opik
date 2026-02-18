import { LLMMessageFormatImplementation } from "../../types";
import { detectLangChainFormat } from "./detector";
import { mapLangChainMessages } from "./mapper";

export const langchainFormat: LLMMessageFormatImplementation = {
  name: "langchain",
  detector: detectLangChainFormat,
  mapper: mapLangChainMessages,
};

export { detectLangChainFormat, mapLangChainMessages };
