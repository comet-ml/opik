import React, { useMemo, useState } from "react";
import { UnfoldVertical, FoldVertical } from "lucide-react";
import { Button } from "@/components/ui/button";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";

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
    <div>
      <Button
        type="button"
        variant="link"
        size="sm"
        className="h-auto p-0 text-muted-slate"
        onClick={() => setIsSampleExpanded(!isSampleExpanded)}
      >
        {isSampleExpanded ? (
          <>
            <FoldVertical className="mr-1 size-4" />
            Collapse dataset item sample
          </>
        ) : (
          <>
            <UnfoldVertical className="mr-1 size-4" />
            View dataset item sample
          </>
        )}
      </Button>
      {isSampleExpanded && (
        <div className="mt-2 rounded-md border border-border">
          <div className="flex h-10 items-center justify-between border-b border-border px-4">
            <span className="comet-body-s text-muted-slate">Payload</span>
          </div>
          <CodeHighlighter
            data={formattedSample}
            language={SUPPORTED_LANGUAGE.json}
          />
        </div>
      )}
    </div>
  );
};

export default DatasetSamplePreview;
