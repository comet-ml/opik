import React from "react";

type NoDataTabProps = {
  title: string;
  description: string;
  imageUrl: string;
  buttons: React.ReactNode;
};

const NoDataTab: React.FC<NoDataTabProps> = ({
  title,
  description,
  imageUrl,
  buttons,
}) => {
  return (
    <div className="flex h-[calc(100vh-188px)] min-h-[500px] w-full min-w-72 items-center justify-stretch py-6">
      <div className="flex size-full flex-col items-center rounded-md border bg-white px-6 py-14">
        <h2 className="comet-title-m">{title}</h2>
        <div className="comet-body-s max-w-[570px] px-4 pb-8 pt-4 text-center text-muted-slate">
          {description}
        </div>
        <div className="flex w-full flex-auto overflow-hidden">
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

export default NoDataTab;
