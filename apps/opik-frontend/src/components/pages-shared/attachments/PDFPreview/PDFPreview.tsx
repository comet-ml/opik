import React, { useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import PDFWorker from "pdfjs-dist/build/pdf.worker.min.mjs?worker";
import { ChevronLeft, ChevronRight } from "lucide-react";

import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";

// Set the worker source for react-pdf
pdfjs.GlobalWorkerOptions.workerPort = new PDFWorker();

interface PDFPreviewProps {
  url: string;
  width?: number;
  showControls?: boolean;
}

const PDFPreview: React.FC<PDFPreviewProps> = ({
  url,
  width = 800,
  showControls = true,
}) => {
  const [totalPages, setTotalPages] = useState<number | null>(null);
  const [page, setPage] = useState<number>(1);

  const disabledPrevious = page === 1;
  const disabledNext = page === totalPages || !totalPages;

  const renderContent = () => {
    return (
      <Document
        file={url}
        onLoadSuccess={({ numPages }) => setTotalPages(numPages)}
        loading={<Loader />}
      >
        <Page
          pageNumber={page}
          width={width}
          renderTextLayer={false}
          renderAnnotationLayer={false}
        />
      </Document>
    );
  };

  const renderNavigation = () => {
    if (!totalPages || !showControls) return null;

    return (
      <div className="absolute inset-x-0 bottom-0 flex flex-row items-center justify-center gap-2 pb-2">
        <div className="flex flex-row items-center gap-2 rounded bg-white p-2 shadow-xl">
          <Button
            variant="outline"
            size="icon-sm"
            disabled={disabledPrevious}
            onClick={() => setPage((prev) => Math.max(prev - 1, 1))}
          >
            <ChevronLeft />
          </Button>
          <span>{`Page ${page} of ${totalPages}`}</span>
          <Button
            variant="outline"
            size="icon-sm"
            disabled={disabledNext}
            onClick={() =>
              setPage((prev) => Math.min(prev + 1, totalPages || prev))
            }
          >
            <ChevronRight />
          </Button>
        </div>
      </div>
    );
  };

  return (
    <div className="size-full">
      <div className="relative flex size-full justify-center overflow-auto pb-10">
        {renderContent()}
      </div>
      {renderNavigation()}
    </div>
  );
};

export default PDFPreview;
