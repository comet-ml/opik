import React, { useMemo, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
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
    // One light box (header + code share the background, no inner divider) to
    // match Figma. The code renders `transparent` so it doesn't draw its own
    // box, and both header and code use the same px-3 gutter so the chevron and
    // the line-number column line up.
    <div className="overflow-hidden rounded-md border border-border bg-primary-foreground">
      {/* The whole row toggles (not just the chevron); Copy is a separate
          control alongside it so it doesn't collapse the panel on click. */}
      <div className="flex h-8 items-center justify-between gap-2 pr-2">
        <button
          type="button"
          className="comet-body-xs flex h-full flex-1 items-center gap-1 px-3 font-normal text-muted-slate"
          onClick={() => setIsSampleExpanded(!isSampleExpanded)}
        >
          {isSampleExpanded ? (
            <ChevronDown className="size-3 shrink-0" />
          ) : (
            <ChevronRight className="size-3 shrink-0" />
          )}
          Sample payload
        </button>
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
        <div className="px-1.5 pb-2">
          <CodeHighlighter
            data={formattedSample}
            language={SUPPORTED_LANGUAGE.json}
            hideCopy
            transparent
          />
        </div>
      )}
    </div>
  );
};

export default DatasetSamplePreview;
