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
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import useDatasetItemUpdateMutation from "@/api/datasets/useDatasetItemUpdateMutation";
import { processInputData } from "@/lib/images";

type DatasetItemPanelContentProps = {
  datasetId: string;
  datasetItemId: string;
};

const DatasetItemPanelContent: React.FunctionComponent<
  DatasetItemPanelContentProps
> = ({ datasetId, datasetItemId }) => {
  const { data, isPending } = useDatasetItemById(
    {
      datasetItemId,
    },
    {
      placeholderData: keepPreviousData,
    },
  );
  const updateMutation = useDatasetItemUpdateMutation();

  const { media, formattedData } = useMemo(
    () => processInputData(data?.data),
    [data?.data],
  );

  const hasMedia = media.length > 0;
  const tags = data?.tags || [];

  const handleAddTag = (newTag: string) => {
    updateMutation.mutate({
      datasetId,
      itemId: datasetItemId,
      item: { tags: [...tags, newTag] },
    });
  };

  const handleDeleteTag = (tag: string) => {
    updateMutation.mutate({
      datasetId,
      itemId: datasetItemId,
      item: { tags: tags.filter((t) => t !== tag) },
    });
  };

  if (isPending) {
    return <Loader />;
  }

  if (!formattedData) {
    return <NoData />;
  }

  return (
    <div className="relative size-full overflow-y-auto p-4">
      <div className="mb-4">
        <div className="mb-2 text-sm font-medium">Tags</div>
        <TagListRenderer
          tags={tags}
          onAddTag={handleAddTag}
          onDeleteTag={handleDeleteTag}
          size="sm"
        />
      </div>

      <Accordion
        type="multiple"
        className="w-full"
        defaultValue={["media", "data"]}
      >
        {hasMedia ? (
          <AccordionItem value="media">
            <AccordionTrigger>Media</AccordionTrigger>
            <AccordionContent>
              <ImagesListWrapper media={media} />
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
