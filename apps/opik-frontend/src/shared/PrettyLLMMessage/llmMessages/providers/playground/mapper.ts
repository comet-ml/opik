import PrettyLLMMessage from "@/shared/PrettyLLMMessage";
import { MessageRole } from "@/shared/PrettyLLMMessage/types";
import { FormatMapper, LLMBlockDescriptor } from "../../types";
import { getPlaygroundOutputText } from "./detector";

export const mapPlaygroundMessages: FormatMapper = (data, prettifyConfig) => {
  if (prettifyConfig?.fieldType !== "output") {
    return { messages: [] };
  }

  const text = getPlaygroundOutputText(data);
  if (text === undefined) {
    return { messages: [] };
  }

  const blocks: LLMBlockDescriptor[] = [
    {
      blockType: "text",
      component: PrettyLLMMessage.TextBlock,
      props: {
        children: text,
        role: "assistant" as MessageRole,
        showMoreButton: true,
      },
    },
  ];

  return {
    messages: [
      {
        id: "output-0",
        role: "assistant" as MessageRole,
        blocks,
      },
    ],
  };
};
