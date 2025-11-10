import React from "react";
import { cn } from "@/lib/utils";

type NoDataPageProps = {
  title: string;
  description: string;
  imageUrl: string;
  buttons: React.ReactNode;
  className?: string;
  height?: number;
};

const NoDataPage: React.FC<NoDataPageProps> = ({
  title,
  description,
  imageUrl,
  buttons,
  className,
  height = 60,
}) => {
  return (
    <div
      style={
        {
          "--page-difference": `${height}px`,
        } as React.CSSProperties
      }
      className={cn(
        "flex h-[calc(100vh-var(--page-difference)-var(--banner-height))] min-h-[500px] w-full min-w-72 items-center justify-stretch py-6",
        className,
      )}
    >
      <div className="flex size-full flex-col items-center rounded-md border bg-background px-6 py-14">
        <h2 className="comet-title-m">{title}</h2>
        <div className="comet-body-s max-w-[570px] px-4 pb-8 pt-4 text-center text-muted-slate">
          {description}
        </div>
        <div className="flex max-h-[450px] w-full flex-auto overflow-hidden">
          <img
            className="m-auto max-h-full max-w-full rounded-md border object-cover"
            src={imageUrl}
            alt="no data image"
          />
        </div>
        <div className="flex flex-wrap justify-center gap-2 pt-8">
          {buttons}
        </div>
      </div>
    </div>
  );
};

export default NoDataPage;
