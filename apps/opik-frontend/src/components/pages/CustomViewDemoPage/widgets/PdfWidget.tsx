import React, { useState } from "react";
import { FileText, ExternalLink } from "lucide-react";
import { WidgetProps } from "@/types/custom-view";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import PDFPreview from "@/components/pages-shared/attachments/PDFPreview/PDFPreview";
import { isSameDomainUrl } from "@/lib/utils";

const PdfWidget: React.FC<WidgetProps> = ({ value, label }) => {
  const [isOpen, setIsOpen] = useState(false);

  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  const pdfUrl = String(value);
  const allowedDomain =
    isSameDomainUrl(pdfUrl) ||
    /^https:\/\/s3\.amazonaws\.com\/([^\s/]+)\/opik\/attachment\/(\S+)$/.test(
      pdfUrl,
    );

  return (
    <>
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-3">{label}</div>
        <div className="flex items-center gap-4">
          <div className="flex size-16 items-center justify-center rounded-md bg-primary-foreground">
            <FileText className="size-8 text-slate-300" strokeWidth={1.33} />
          </div>
          <div className="flex flex-1 flex-col gap-2">
            {allowedDomain && (
              <Button
                variant="outline"
                size="sm"
                className="gap-2"
                onClick={() => setIsOpen(true)}
              >
                <FileText className="size-4" />
                Preview PDF
              </Button>
            )}
            <Button variant="outline" size="sm" asChild className="gap-2">
              <a href={pdfUrl} target="_blank" rel="noopener noreferrer">
                <ExternalLink className="size-4" />
                Open in New Tab
              </a>
            </Button>
          </div>
        </div>
      </div>

      {allowedDomain && (
        <Dialog open={isOpen} onOpenChange={setIsOpen}>
          <DialogContent className="w-[90vw]">
            <DialogHeader>
              <DialogTitle>{label}</DialogTitle>
            </DialogHeader>
            <div className="h-[80vh]">
              <PDFPreview url={pdfUrl} />
            </div>
          </DialogContent>
        </Dialog>
      )}
    </>
  );
};

export default PdfWidget;
