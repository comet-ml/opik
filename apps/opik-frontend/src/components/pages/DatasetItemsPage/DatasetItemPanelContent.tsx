import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import { processInputData } from "@/lib/images";

type DatasetItemPanelContentProps = {
  datasetItemId: string;
};

const DatasetItemPanelContent: React.FunctionComponent<
  DatasetItemPanelContentProps
> = ({ datasetItemId }) => {
  const { data, isPending } = useDatasetItemById(
    {
      datasetItemId,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const { images, formattedData } = useMemo(
    () => processInputData(data?.data),
    [data?.data],
  );

  const hasImages = images.length > 0;

  if (isPending) {
    return <Loader />;
  }

  if (!formattedData) {
    return <NoData />;
  }

  return (
    <div className="relative size-full overflow-y-auto p-4">
      <Accordion
        type="multiple"
        className="w-full"
        defaultValue={["images", "data"]}
      >
        {hasImages ? (
          <AccordionItem value="images">
            <AccordionTrigger>Images</AccordionTrigger>
            <AccordionContent>
              <ImagesListWrapper images={images} />
            </AccordionContent>
          </AccordionItem>
        ) : null}

        <AccordionItem value="data">
          <AccordionTrigger>Data</AccordionTrigger>
          <AccordionContent>
            <SyntaxHighlighter data={formattedData} />
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default DatasetItemPanelContent;
