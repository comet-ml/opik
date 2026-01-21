import React from "react";
import { File, Download, ExternalLink } from "lucide-react";
import { WidgetProps } from "@/types/custom-view";
import { Button } from "@/components/ui/button";
import { isSameDomainUrl } from "@/lib/utils";

const FileWidget: React.FC<WidgetProps> = ({ value, label }) => {
  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  const fileUrl = String(value);
  const allowedDomain =
    isSameDomainUrl(fileUrl) ||
    /^https:\/\/s3\.amazonaws\.com\/([^\s/]+)\/opik\/attachment\/(\S+)$/.test(
      fileUrl,
    );
  const showDownload = fileUrl.startsWith("data:") || allowedDomain;

  // Extract filename from URL if possible
  const getFileName = (url: string): string => {
    try {
      const urlObj = new URL(url);
      const pathname = urlObj.pathname;
      const filename = pathname.split("/").pop();
      return filename || "file";
    } catch {
      return "file";
    }
  };

  const fileName = getFileName(fileUrl);

  return (
    <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
      <div className="comet-body-s-accented mb-3">{label}</div>
      <div className="flex items-center gap-4">
        <div className="flex size-16 items-center justify-center rounded-md bg-primary-foreground">
          <File className="size-8 text-slate-300" strokeWidth={1.33} />
        </div>
        <div className="flex flex-1 flex-col gap-2">
          <div className="truncate text-sm" title={fileName}>
            {fileName}
          </div>
          <div className="flex gap-2">
            {showDownload && (
              <Button variant="outline" size="sm" asChild className="gap-2">
                <a
                  href={fileUrl}
                  download={fileName}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <Download className="size-4" />
                  Download
                </a>
              </Button>
            )}
            <Button variant="outline" size="sm" asChild className="gap-2">
              <a href={fileUrl} target="_blank" rel="noopener noreferrer">
                <ExternalLink className="size-4" />
                Open
              </a>
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default FileWidget;
