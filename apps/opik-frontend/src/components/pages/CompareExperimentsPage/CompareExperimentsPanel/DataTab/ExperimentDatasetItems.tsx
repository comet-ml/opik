import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import NoData from "@/components/shared/NoData/NoData";
import React, { useMemo } from "react";
import { DatasetItem } from "@/types/datasets";
import { pick } from "lodash";
import { extractImageUrls } from "@/lib/images";

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

  const imagesUrls = useMemo(
    () => extractImageUrls(selectedData),
    [selectedData],
  );
  const showImages = imagesUrls?.length > 0;

  if (!showImages) {
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
      defaultValue={["images", "data"]}
    >
      {showImages ? (
        <AccordionItem value="images" className="border-t">
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
        <AccordionTrigger>Selected data</AccordionTrigger>
        <AccordionContent>
          {data ? (
            <SyntaxHighlighter
              data={selectedData || {}}
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
