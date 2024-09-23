import React, { useMemo } from "react";
import get from "lodash/get";
import isString from "lodash/isString";
import { Span, Trace } from "@/types/traces";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

export type ImageContent = {
  type: "image_url";
  image_url: {
    url: string;
  };
};

const isImageContent = (content?: Partial<ImageContent>) => {
  try {
    return content?.type === "image_url" && isString(content?.image_url?.url);
  } catch (error) {
    return false;
  }
};

function extractImageUrls(messages: unknown) {
  if (!Array.isArray(messages)) return [];

  const images: string[] = [];

  messages.forEach((message) => {
    const imageContent: ImageContent[] = Array.isArray(message?.content)
      ? message.content.filter(isImageContent)
      : [];

    images.push(...imageContent.map((content) => content.image_url.url));
  });

  return images;
}

type InputOutputTabProps = {
  data: Trace | Span;
};

const InputOutputTab: React.FunctionComponent<InputOutputTabProps> = ({
  data,
}) => {
  const imagesUrls = useMemo(
    () => extractImageUrls(get(data, ["input", "messages"], [])),
    [data],
  );

  const hasImages = imagesUrls.length > 0;

  const openSections = useMemo(() => {
    return hasImages ? ["images", "input", "output"] : ["input", "output"];
  }, [hasImages]);

  return (
    <Accordion type="multiple" className="w-full" defaultValue={openSections}>
      {hasImages && (
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
      )}
      <AccordionItem value="input">
        <AccordionTrigger>Input</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.input} />
        </AccordionContent>
      </AccordionItem>
      <AccordionItem value="output">
        <AccordionTrigger>Output</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.output} />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default InputOutputTab;
