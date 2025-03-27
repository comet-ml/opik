import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import { extractImageUrls, replaceBase64ImageValues } from "@/lib/images";

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
  const formattedData = useMemo(
    () => replaceBase64ImageValues(data?.data),
    [data?.data],
  );

  const imagesUrls = useMemo(() => extractImageUrls(data?.data), [data?.data]);
  const hasImages = imagesUrls.length > 0;

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
              <div className="flex flex-wrap gap-2">
                {imagesUrls.map((imageUrl, index) => {
                  return (
                    <div
                      key={index + imageUrl.substring(0, 10)}
                      className="h-[200px] max-w-[300px] rounded-md border p-4"
                    >
                      <img
                        src={imageUrl}
                        loading="lazy"
                        alt={`image-${index}`}
                        className="size-full object-contain"
                      />
                    </div>
                  );
                })}
              </div>
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
