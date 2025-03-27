import React, { useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import { extractImageUrls, replaceBase64ImageValues } from "@/lib/images";

type InputOutputTabProps = {
  data: Trace | Span;
};

const InputOutputTab: React.FunctionComponent<InputOutputTabProps> = ({
  data,
}) => {
  const imagesUrls = useMemo(() => extractImageUrls(data.input), [data.input]);
  const hasImages = imagesUrls.length > 0;

  const formattedOutput = useMemo(
    () => replaceBase64ImageValues(data.output),
    [data.output],
  );
  const formattedInput = useMemo(
    () => replaceBase64ImageValues(data.input),
    [data.input],
  );

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
          <SyntaxHighlighter
            data={formattedInput}
            prettifyConfig={{ fieldType: "input" }}
          />
        </AccordionContent>
      </AccordionItem>
      <AccordionItem value="output">
        <AccordionTrigger>Output</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter
            data={formattedOutput}
            prettifyConfig={{ fieldType: "output" }}
          />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default InputOutputTab;
