import React from "react";
import { ExternalLink } from "lucide-react";

import { Button } from "@/ui/button";
import { Description } from "@/ui/description";

type DatasetUploadDescriptionProps = {
  fileSizeLimit: number;
  docsUrl: string;
  className?: string;
};

const DatasetUploadDescription: React.FC<DatasetUploadDescriptionProps> = ({
  fileSizeLimit,
  docsUrl,
  className = "tracking-normal",
}) => (
  <Description className={className}>
    Supported formats: .csv, .json, .jsonl/.ndjson. File can be up to{" "}
    {fileSizeLimit}MB in size and will be processed in the background.
    <Button variant="link" size="sm" className="h-5 px-1" asChild>
      <a href={docsUrl} target="_blank" rel="noopener noreferrer">
        Learn more
        <ExternalLink className="ml-0.5 size-3 shrink-0" />
      </a>
    </Button>
  </Description>
);

export default DatasetUploadDescription;