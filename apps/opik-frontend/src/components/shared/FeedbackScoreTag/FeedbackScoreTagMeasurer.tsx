import { TraceFeedbackScore } from "@/types/traces";
import React, { useRef, useLayoutEffect } from "react";
import FeedbackScoreTag from "./FeedbackScoreTag";

type FeedbackScoreTagMeasurer = {
  tagList: TraceFeedbackScore[];
  onMeasure: (result: number[]) => void;
};
const FeedbackScoreTagMeasurer: React.FC<FeedbackScoreTagMeasurer> = ({
  tagList,
  onMeasure,
}) => {
  const containerRef = useRef(null);
  const tagRefs = useRef<HTMLDivElement[]>([]);

  useLayoutEffect(() => {
    const measurements: number[] = [];

    tagRefs.current.forEach((tag) => {
      measurements.push(tag.getBoundingClientRect().width);
    });

    onMeasure(tagRefs.current.map((tag) => tag.getBoundingClientRect().width));

    return () => {
      tagRefs.current = [];
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onTagRef = (tag: HTMLDivElement) => {
    tagRefs.current.push(tag);
  };

  if (tagRefs.current.length) return;

  return (
    <div
      ref={containerRef}
      aria-hidden="true"
      className="invisible absolute  flex size-full items-center justify-start gap-1.5 p-0 py-1"
    >
      {tagList.map<React.ReactNode>((item) => (
        <div key={item.name} ref={onTagRef}>
          <FeedbackScoreTag
            label={item.name}
            value={item.value}
            reason={item.reason}
          />
        </div>
      ))}
    </div>
  );
};

export default FeedbackScoreTagMeasurer;
