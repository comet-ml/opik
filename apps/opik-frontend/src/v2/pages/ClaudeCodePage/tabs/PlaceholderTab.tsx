import React from "react";

interface Props {
  title: string;
}

const PlaceholderTab: React.FC<Props> = ({ title }) => {
  return (
    <div className="flex h-[480px] flex-col items-center justify-center rounded-lg border border-dashed border-border bg-background text-muted-foreground">
      <div className="comet-body-accented text-foreground">{title}</div>
      <div className="comet-body-s mt-1">Coming next</div>
    </div>
  );
};

export default PlaceholderTab;
