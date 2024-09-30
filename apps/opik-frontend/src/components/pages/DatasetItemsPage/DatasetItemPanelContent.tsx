import React from "react";
import { keepPreviousData } from "@tanstack/react-query";
import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import useDatasetById from "@/api/datasets/useDatasetById";

type DatasetItemPanelContentProps = {
  datasetId: string;
  datasetItemId: string;
};

const DatasetItemPanelContent: React.FunctionComponent<
  DatasetItemPanelContentProps
> = ({ datasetId, datasetItemId }) => {
  const { data: dataset } = useDatasetById({
    datasetId,
  });

  const { data, isPending } = useDatasetItemById(
    {
      datasetItemId,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  if (isPending) {
    return <Loader />;
  }

  if (!data) {
    return <NoData />;
  }

  return (
    <div className="relative size-full">
      <div className="size-full overflow-y-auto p-4">
        <div className="my-4 flex flex-row gap-1">
          <div className="font-bold">Dataset:</div>
          <div>{dataset?.name}</div>
        </div>
        <Accordion
          type="multiple"
          className="w-full"
          defaultValue={["input", "output"]}
        >
          <AccordionItem value="input">
            <AccordionTrigger>Input</AccordionTrigger>
            <AccordionContent>
              <SyntaxHighlighter data={data.input} />
            </AccordionContent>
          </AccordionItem>
          <AccordionItem value="output">
            <AccordionTrigger>Expected output</AccordionTrigger>
            <AccordionContent>
              <SyntaxHighlighter data={data.expected_output} />
            </AccordionContent>
          </AccordionItem>
          <AccordionItem value="metadata">
            <AccordionTrigger>Metadata</AccordionTrigger>
            <AccordionContent>
              <SyntaxHighlighter data={data.metadata ?? {}} />
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      </div>
    </div>
  );
};

export default DatasetItemPanelContent;
