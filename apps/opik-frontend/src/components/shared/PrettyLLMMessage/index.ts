import PrettyLLMMessageContainer from "./PrettyLLMMessageContainer";
import PrettyLLMMessageRoot from "./PrettyLLMMessageRoot";
import PrettyLLMMessageHeader from "./PrettyLLMMessageHeader";
import PrettyLLMMessageContent from "./PrettyLLMMessageContent";
import PrettyLLMMessageFooter from "./PrettyLLMMessageFooter";
import PrettyLLMMessageFinishReason from "./PrettyLLMMessageFinishReason";
import PrettyLLMMessageUsage from "./PrettyLLMMessageUsage";
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
  FinishReason: PrettyLLMMessageFinishReason,
  Usage: PrettyLLMMessageUsage,
  TextBlock: PrettyLLMMessageTextBlock,
  ImageBlock: PrettyLLMMessageImageBlock,
  AudioPlayerBlock: PrettyLLMMessageAudioPlayerBlock,
  VideoBlock: PrettyLLMMessageVideoBlock,
  CodeBlock: PrettyLLMMessageCodeBlock,
};

export default PrettyLLMMessage;

export type {
  MessageRole,
  PrettyLLMMessageContainerProps,
  PrettyLLMMessageRootProps,
  PrettyLLMMessageHeaderProps,
  PrettyLLMMessageContentProps,
  PrettyLLMMessageFooterProps,
  PrettyLLMMessageFinishReasonProps,
  PrettyLLMMessageUsageProps,
  PrettyLLMMessageTextBlockProps,
  PrettyLLMMessageImageBlockProps,
  PrettyLLMMessageAudioPlayerBlockProps,
  PrettyLLMMessageVideoBlockProps,
  PrettyLLMMessageCodeBlockProps,
} from "./types";
