import React from "react";
import { ExternalLink } from "lucide-react";
import { WidgetProps } from "@/types/custom-view";
import { Button } from "@/components/ui/button";

const LinkWidget: React.FC<WidgetProps> = ({ value, label }) => {
  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  const url = String(value);

  // Validate URL
  let isValidUrl = false;
  try {
    new URL(url);
    isValidUrl = true;
  } catch {
    isValidUrl = false;
  }

  if (!isValidUrl) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">Invalid URL: {url}</div>
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
      <div className="comet-body-s-accented mb-2">{label}</div>
      <Button variant="outline" size="sm" asChild className="gap-2">
        <a href={url} target="_blank" rel="noopener noreferrer">
          <ExternalLink className="size-4" />
          Open Link
        </a>
      </Button>
      <div className="mt-2 truncate text-xs text-muted-slate" title={url}>
        {url}
      </div>
    </div>
  );
};

export default LinkWidget;
