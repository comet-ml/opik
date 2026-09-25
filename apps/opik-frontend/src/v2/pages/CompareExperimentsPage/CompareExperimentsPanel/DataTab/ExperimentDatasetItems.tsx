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
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import { useExperimentItemMedia } from "@/hooks/useExperimentItemMedia";
import ExperimentMessagesViewer from "@/v2/pages-shared/experiments/ExperimentMessagesViewer/ExperimentMessagesViewer";
import { partitionMessageFields } from "@/v2/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/partitionMessageFields";

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

  const { messageData, remainingData } = useMemo(
    () => partitionMessageFields(transformedData),
    [transformedData],
  );

  const hasMessages = Object.keys(messageData).length > 0;
  const hasRemaining = Object.keys(remainingData).length > 0;

  // Media must not bypass the messages check: a multimodal conversation is still
  // a conversation, and rendering it as JSON was the gap this replaces.
  const messagesViewer = (
    <MediaProvider media={unifiedMedia}>
      <div className="flex flex-col gap-2">
        <ExperimentMessagesViewer
          input={messageData}
          preserveKey="compare-experiment-input-messages"
        />
        {hasRemaining && (
          <SyntaxHighlighter
            data={remainingData}
            prettifyConfig={{ fieldType: "input" }}
            preserveKey="syntax-highlighter-compare-experiment-input-remaining"
          />
        )}
      </div>
    </MediaProvider>
  );

  if (!showMedia) {
    if (data && hasMessages) {
      return messagesViewer;
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
            messagesViewer
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
