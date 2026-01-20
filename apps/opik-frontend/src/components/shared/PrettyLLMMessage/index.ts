import PrettyLLMMessageContainer from "./PrettyLLMMessageContainer";
import PrettyLLMMessageRoot from "./PrettyLLMMessageRoot";
import PrettyLLMMessageHeader from "./PrettyLLMMessageHeader";
import PrettyLLMMessageContent from "./PrettyLLMMessageContent";
import PrettyLLMMessageFooter from "./PrettyLLMMessageFooter";
import PrettyLLMMessageTextBlock from "./PrettyLLMMessageTextBlock";
import PrettyLLMMessageImageBlock from "./PrettyLLMMessageImageBlock";
import PrettyLLMMessageAudioPlayerBlock from "./PrettyLLMMessageAudioPlayerBlock";
import PrettyLLMMessageVideoBlock from "./PrettyLLMMessageVideoBlock";
import PrettyLLMMessageCodeBlock from "./PrettyLLMMessageCodeBlock";

const PrettyLLMMessage = {
  Container: PrettyLLMMessageContainer,
  Root: PrettyLLMMessageRoot,
  Header: PrettyLLMMessageHeader,
  Content: PrettyLLMMessageContent,
  Footer: PrettyLLMMessageFooter,
  TextBlock: PrettyLLMMessageTextBlock,
  ImageBlock: PrettyLLMMessageImageBlock,
  AudioPlayerBlock: PrettyLLMMessageAudioPlayerBlock,
  VideoBlock: PrettyLLMMessageVideoBlock,
  CodeBlock: PrettyLLMMessageCodeBlock,
};

export default PrettyLLMMessage;

export { PrettyLLMMessageContainer };
export { PrettyLLMMessageRoot };
export { PrettyLLMMessageHeader };
export { PrettyLLMMessageContent };
export { PrettyLLMMessageFooter };
export { PrettyLLMMessageTextBlock };
export { PrettyLLMMessageImageBlock };
export { PrettyLLMMessageAudioPlayerBlock };
export { PrettyLLMMessageVideoBlock };
export { PrettyLLMMessageCodeBlock };

export type {
  MessageRole,
  PrettyLLMMessageContainerProps,
  PrettyLLMMessageRootProps,
  PrettyLLMMessageHeaderProps,
  PrettyLLMMessageContentProps,
  PrettyLLMMessageFooterProps,
  PrettyLLMMessageTextBlockProps,
  PrettyLLMMessageImageBlockProps,
  PrettyLLMMessageAudioPlayerBlockProps,
  PrettyLLMMessageVideoBlockProps,
  PrettyLLMMessageCodeBlockProps,
} from "./types";
