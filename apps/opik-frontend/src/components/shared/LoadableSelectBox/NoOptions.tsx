import React from "react";
import isFunction from "lodash/isFunction";
import { Button } from "@/components/ui/button";

export type SelectBoxProps = {
  text?: string;
  onLoadMore?: () => void;
};

export const NoOptions = ({ text = "", onLoadMore }: SelectBoxProps) => {
  return (
    <div className="flex min-h-24 flex-col items-center justify-center px-6 py-4">
      <div className="comet-body-s text-center text-muted-slate">{text}</div>
      {isFunction(onLoadMore) && (
        <Button onClick={onLoadMore} variant="link">
          Load more items
        </Button>
      )}
    </div>
  );
};
export default NoOptions;
