import React, { useCallback, useEffect, useState } from "react";
import isUndefined from "lodash/isUndefined";
import isNumber from "lodash/isNumber";
import sortBy from "lodash/sortBy";
import { X } from "lucide-react";

import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { TraceFeedbackScore } from "@/types/traces";
import { Button } from "@/components/ui/button";
import { isNumericFeedbackScoreValid } from "@/lib/traces";

const SET_VALUE_DEBOUNCE_DELAY = 500;

type AnnotateRowProps = {
  name: string;
  feedbackDefinition?: FeedbackDefinition;
  feedbackScore?: TraceFeedbackScore;
  spanId?: string;
  traceId: string;
};

const AnnotateRow: React.FunctionComponent<AnnotateRowProps> = ({
  name,
  feedbackDefinition,
  feedbackScore,
  spanId,
  traceId,
}) => {
  const [categoryName, setCategoryName] = useState(
    feedbackScore?.category_name,
  );
  useEffect(() => {
    setCategoryName(feedbackScore?.category_name);
  }, [feedbackScore?.category_name, traceId, spanId]);

  const [value, setValue] = useState<number | "">(
    isNumber(feedbackScore?.value) ? feedbackScore?.value : "",
  );
  useEffect(() => {
    setValue(isNumber(feedbackScore?.value) ? feedbackScore?.value : "");
  }, [feedbackScore?.value, spanId, traceId]);

  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const deleteFeedbackScore = useCallback(() => {
    feedbackScoreDeleteMutation.mutate({
      traceId,
      spanId,
      name: feedbackScore?.name ?? "",
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackScore?.name, traceId, spanId]);

  const setTraceFeedbackScoreMutation = useTraceFeedbackScoreSetMutation();

  const handleChangeValue = useCallback(
    (value: number, categoryName?: string) => {
      setTraceFeedbackScoreMutation.mutate({
        categoryName,
        name,
        spanId,
        traceId,
        value,
      });
    },
    // setTraceFeedbackScoreMutation re triggers this memo when it should not
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [name, spanId, traceId],
  );

  const renderOptions = (feedbackDefinition: FeedbackDefinition) => {
    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      return (
        <DebounceInput
          className="min-w-[100px]"
          max={feedbackDefinition.details.max}
          min={feedbackDefinition.details.min}
          step="any"
          dimension="sm"
          delay={SET_VALUE_DEBOUNCE_DELAY}
          onChangeValue={(value) => {
            const newValue = value === "" ? "" : Number(value);

            setValue(newValue);
            if (
              isNumericFeedbackScoreValid(feedbackDefinition.details, newValue)
            ) {
              handleChangeValue(newValue as number);
            }
          }}
          placeholder="Score"
          type="number"
          value={value}
        />
      );
    }

    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      return (
        <ToggleGroup
          className="w-fit"
          onValueChange={(newCategoryName) => {
            const categoryEntry = Object.entries(
              feedbackDefinition.details.categories,
            ).find(([categoryName]) => categoryName === newCategoryName);

            if (categoryEntry) {
              const categoryValue = categoryEntry[1];

              setCategoryName(newCategoryName);
              setValue(categoryValue);
              handleChangeValue(categoryValue, newCategoryName);
            }
          }}
          type="single"
          size="sm"
          value={String(categoryName)}
        >
          {sortBy(
            Object.entries(feedbackDefinition.details.categories).map(
              ([name, value]) => ({
                name,
                value,
              }),
            ),
            "name",
          ).map(({ name, value }) => {
            return (
              <ToggleGroupItem key={name} value={name}>
                <div className="text-nowrap">
                  {name} ({value})
                </div>
              </ToggleGroupItem>
            );
          })}
        </ToggleGroup>
      );
    }

    return null;
  };

  return (
    <>
      <div className="flex items-center overflow-hidden border-b border-border px-1 py-2">
        <ColoredTag label={name} />
      </div>
      <div
        className="flex items-center overflow-hidden border-b border-border px-1 py-2"
        data-test-value={name}
      >
        {feedbackDefinition ? (
          <div className="overflow-auto">
            {renderOptions(feedbackDefinition)}
          </div>
        ) : (
          <div>{feedbackScore?.value}</div>
        )}
      </div>
      <div className="flex items-center overflow-hidden border-b border-border px-1 py-2">
        {!isUndefined(feedbackScore?.value) && (
          <Button
            variant="minimal"
            size="icon-sm"
            onClick={deleteFeedbackScore}
          >
            <X className="size-4" />
          </Button>
        )}
      </div>
    </>
  );
};

export default AnnotateRow;
