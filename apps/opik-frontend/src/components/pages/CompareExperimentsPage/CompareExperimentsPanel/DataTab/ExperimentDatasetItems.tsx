import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import NoData from "@/components/shared/NoData/NoData";
import React, { useMemo } from "react";
import { DatasetItem } from "@/types/datasets";
import { pick } from "lodash";
import { useProcessedInputData } from "@/hooks/useProcessedInputData";

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

  if (!showMedia) {
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
          {formattedData ? (
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
