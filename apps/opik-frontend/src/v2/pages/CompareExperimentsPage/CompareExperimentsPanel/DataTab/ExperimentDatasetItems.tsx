import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import ImagesListWrapper from "@/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import NoData from "@/shared/NoData/NoData";
import React, { useMemo } from "react";
import { DatasetItem } from "@/types/datasets";
import { pick } from "lodash";
import { useProcessedInputData } from "@/hooks/useProcessedInputData";
import {
  MediaProvider,
  mapAndCombineMessages,
} from "@/shared/PrettyLLMMessage/llmMessages";
import { useExperimentItemMedia } from "@/hooks/useExperimentItemMedia";
import ExperimentMessagesViewer from "@/v2/pages-shared/experiments/ExperimentMessagesViewer/ExperimentMessagesViewer";

interface ExperimentDatasetItemsProps {
  data: DatasetItem["data"] | undefined;
  selectedKeys: string[];
}

const ExperimentDatasetItems = ({
  data,
  selectedKeys,
}: ExperimentDatasetItemsProps) => {
  const selectedData: DatasetItem["data"] = useMemo(() => {
    if (!selectedKeys.length || !data) {
      return {};
    }

    return pick(data, selectedKeys);
  }, [selectedKeys, data]);

  const { media, formattedData } = useProcessedInputData(selectedData);

  const showMedia = media?.length > 0;

  // Placeholders in the text resolve through MediaProvider, which needs unified
  // media items rather than the parsed ones ImagesListWrapper renders.
  const { media: unifiedMedia, transformedOutput: transformedData } =
    useExperimentItemMedia({ output: selectedData });

  // Dataset columns hold arbitrary values, so only the ones that actually carry
  // a recognised LLM message format get the role-by-role treatment.
  const hasMessages = useMemo(
    () => mapAndCombineMessages(transformedData, undefined).messages.length > 0,
    [transformedData],
  );

  // Media must not bypass the messages check: a multimodal conversation is still
  // a conversation, and rendering it as JSON was the gap this replaces.
  const messagesViewer = (
    <ExperimentMessagesViewer
      input={transformedData}
      preserveKey="compare-experiment-input-messages"
    />
  );

  if (!showMedia) {
    if (data && hasMessages) {
      return unifiedMedia.length ? (
        <MediaProvider media={unifiedMedia}>{messagesViewer}</MediaProvider>
      ) : (
        messagesViewer
      );
    }

    return data ? (
      <SyntaxHighlighter
        data={selectedData}
        prettifyConfig={{ fieldType: "input" }}
        preserveKey="syntax-highlighter-compare-experiment-input"
      />
    ) : (
      <NoData />
    );
  }

  return (
    <Accordion
      type="multiple"
      className="w-full"
      defaultValue={["media", "data"]}
    >
      {showMedia ? (
        <AccordionItem value="media" className="border-t">
          <AccordionTrigger>Media</AccordionTrigger>
          <AccordionContent>
            <ImagesListWrapper media={media} />
          </AccordionContent>
        </AccordionItem>
      ) : null}

      <AccordionItem value="data">
        <AccordionTrigger>Selected data</AccordionTrigger>
        <AccordionContent>
          {hasMessages ? (
            <MediaProvider media={unifiedMedia}>{messagesViewer}</MediaProvider>
          ) : formattedData ? (
            <SyntaxHighlighter
              data={formattedData ?? {}}
              prettifyConfig={{ fieldType: "input" }}
              preserveKey="syntax-highlighter-compare-experiment-input"
            />
          ) : (
            <NoData />
          )}
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default ExperimentDatasetItems;
