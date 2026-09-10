import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import isNumber from "lodash/isNumber";
import { Copy, Trash, X } from "lucide-react";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import { TraceFeedbackScore } from "@/types/traces";
import { Button } from "@/ui/button";
import ColoredTagNew from "@/shared/ColoredTag/ColoredTagNew";
import { updateTextAreaHeight } from "@/lib/utils";
import { Textarea } from "@/ui/textarea";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { findValueByAuthor, hasValuesByAuthor } from "@/lib/feedback-scores";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import copy from "clipboard-copy";
import { useToast } from "@/ui/use-toast";
import { UpdateFeedbackScoreData } from "./types";
import { useLoggedInUserNameOrOpenSourceDefaultUser } from "@/store/AppStore";
import FeedbackScoreValueInput, {
  FeedbackScoreValue,
} from "../../FeedbackScoreValueInput/FeedbackScoreValueInput";

const SET_VALUE_DEBOUNCE_DELAY = 500;

type AnnotateRowProps = {
  name: string;
  feedbackDefinition?: FeedbackDefinition;
  feedbackScore?: TraceFeedbackScore;
  onUpdateFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  onDeleteFeedbackScore: (name: string) => void;
};

const AnnotateRow: React.FunctionComponent<AnnotateRowProps> = ({
  name,
  feedbackDefinition,
  feedbackScore,
  onUpdateFeedbackScore,
  onDeleteFeedbackScore,
}) => {
  const textAreaRef = useRef<HTMLTextAreaElement | null>(null);
  const { toast } = useToast();
  const userName = useLoggedInUserNameOrOpenSourceDefaultUser();

  const feedbackScoreData = useMemo(() => {
    if (!feedbackScore) {
      return {
        value: "" as const,
        reason: "",
        category_name: "",
      };
    }

    if (hasValuesByAuthor(feedbackScore) && userName) {
      const userValue = findValueByAuthor(
        feedbackScore.value_by_author,
        userName,
      );
      const rawValue = userValue?.value;

      return {
        value: isNumber(rawValue) ? rawValue : ("" as const),
        reason: userValue?.reason ?? "",
        category_name: userValue?.category_name ?? "",
      };
    }

    const rawValue = feedbackScore?.value ?? "";
    return {
      value: isNumber(rawValue) ? rawValue : ("" as const),
      reason: feedbackScore?.reason ?? "",
      category_name: feedbackScore?.category_name ?? "",
    };
  }, [feedbackScore, userName]);

  const [categoryName, setCategoryName] = useState<string | undefined>(
    feedbackScoreData.category_name,
  );

  useEffect(() => {
    setCategoryName(feedbackScoreData.category_name);
  }, [feedbackScoreData.category_name]);

  const [value, setValue] = useState<number | "">(feedbackScoreData.value);
  useEffect(() => {
    setValue(feedbackScoreData.value);

    if (feedbackScoreData.value === "") {
      setReasonValue("");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackScoreData.value]);

  const onChangeTextAreaTriggered = useCallback(() => {
    updateTextAreaHeight(textAreaRef.current);
  }, []);

  const handleChangeReason = useCallback(
    (reason?: string) => {
      onUpdateFeedbackScore({
        categoryName,
        name,
        reason,
        value: value as number,
      });
    },
    [name, value, categoryName, onUpdateFeedbackScore],
  );

  const {
    value: reasonValue,
    onChange: onReasonChange,
    onFocus: onReasonFocus,
    onBlur: onReasonBlur,
    onReset: onReasonReset,
    setInputValue: setReasonValue,
  } = useDebouncedValue({
    initialValue: feedbackScoreData.reason,
    onDebouncedChange: handleChangeReason,
    delay: SET_VALUE_DEBOUNCE_DELAY,
    onChange: onChangeTextAreaTriggered,
  });

  const handleChangeValue = useCallback(
    (newValue: number, newCategoryName?: string) => {
      onUpdateFeedbackScore({
        categoryName: newCategoryName,
        name,
        value: newValue,
        reason: reasonValue,
      });
    },
    [name, reasonValue, onUpdateFeedbackScore],
  );

  const deleteFeedbackScore = useCallback(() => {
    onDeleteFeedbackScore(name);
    setReasonValue("");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, setReasonValue]);

  const handleValueChange = useCallback(
    (update: FeedbackScoreValue) => {
      if (update.status === "invalid") {
        return;
      }

      if (update.status === "empty" || update.value === undefined) {
        setValue("");
        deleteFeedbackScore();
        return;
      }

      setCategoryName(update.categoryName);
      setValue(update.value);
      handleChangeValue(update.value, update.categoryName);
    },
    [deleteFeedbackScore, handleChangeValue],
  );

  const handleCopyReasonClick = async (v: string) => {
    await copy(v);

    toast({
      description: "Reason successfully copied to clipboard",
    });
  };

  return (
    <>
      <div className="flex items-center overflow-hidden border-t border-border p-1 pl-0">
        <ColoredTagNew label={name} />
      </div>
      <div
        className="flex items-center overflow-hidden border-t border-border p-1"
        data-test-value={name}
        data-testid={`annotate-score-row-${name}`}
      >
        {feedbackDefinition ? (
          <div className="min-w-0 flex-1 overflow-auto">
            <FeedbackScoreValueInput
              feedbackDefinition={feedbackDefinition}
              value={value}
              categoryName={categoryName}
              onChange={handleValueChange}
              testIdPrefix="annotate"
            />
          </div>
        ) : (
          <div>{feedbackScoreData?.value}</div>
        )}
      </div>
      <div className="flex items-center overflow-hidden border-t border-border px-0.5">
        {feedbackScoreData?.value !== "" && (
          <Button
            variant="minimal"
            size="icon-xs"
            aria-label="Clear score"
            onClick={deleteFeedbackScore}
          >
            <X />
          </Button>
        )}
      </div>

      <div className="group/reason-field relative col-span-2 px-1 pb-1">
        <Textarea
          placeholder="Add a reason..."
          value={reasonValue}
          onChange={onReasonChange}
          onFocus={onReasonFocus}
          onBlur={onReasonBlur}
          disabled={value === ""}
          className="min-h-6 resize-none overflow-hidden py-1 pt-[4px]"
          data-testid={`annotate-score-reason-${name}`}
          ref={(e) => {
            textAreaRef.current = e;
            updateTextAreaHeight(e, 32);
          }}
        />
        {feedbackScoreData?.reason && value !== "" && (
          <div className="absolute right-2 top-1 hidden gap-1 group-hover/reason-field:flex">
            <TooltipWrapper content="Copy">
              <Button
                size="icon-2xs"
                variant="outline"
                onClick={() => handleCopyReasonClick(feedbackScoreData.reason!)}
              >
                <Copy />
              </Button>
            </TooltipWrapper>

            <TooltipWrapper content="Clear">
              <Button variant="outline" size="icon-2xs" onClick={onReasonReset}>
                <Trash />
              </Button>
            </TooltipWrapper>
          </div>
        )}
      </div>
      <div></div>
    </>
  );
};

export default AnnotateRow;
