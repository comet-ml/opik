import React, { useMemo, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Button } from "@/ui/button";
import CopyButton from "@/shared/CopyButton/CopyButton";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { SUPPORTED_LANGUAGE } from "@/constants/codeLanguage";

interface DatasetSamplePreviewProps {
  datasetSample: object;
}

const DatasetSamplePreview: React.FC<DatasetSamplePreviewProps> = ({
  datasetSample,
}) => {
  const [isSampleExpanded, setIsSampleExpanded] = useState(false);

  const formattedSample = useMemo(
    () => JSON.stringify(datasetSample, null, 2),
    [datasetSample],
  );

  return (
    <div className="overflow-hidden rounded-md border border-border bg-primary-foreground">
      <div className="flex h-8 items-center justify-between gap-2 px-3">
        <Button
          type="button"
          variant="link"
          size="sm"
          className="comet-body-xs h-auto p-0 font-normal text-muted-slate hover:no-underline"
          onClick={() => setIsSampleExpanded(!isSampleExpanded)}
        >
          {isSampleExpanded ? (
            <ChevronDown className="mr-1 size-3" />
          ) : (
            <ChevronRight className="mr-1 size-3" />
          )}
          Sample payload
        </Button>
        {isSampleExpanded && (
          <CopyButton
            text={formattedSample}
            tooltipText="Copy sample payload"
            size="icon-2xs"
            variant="ghost"
          />
        )}
      </div>
      {isSampleExpanded && (
        <div className="border-t border-border bg-background">
          <CodeHighlighter
            data={formattedSample}
            language={SUPPORTED_LANGUAGE.json}
            hideCopy
          />
        </div>
      )}
    </div>
  );
};

export default DatasetSamplePreview;
