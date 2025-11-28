import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import isNumber from "lodash/isNumber";
import sortBy from "lodash/sortBy";
import { Copy, MessageSquareMore, Trash, X } from "lucide-react";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { TraceFeedbackScore } from "@/types/traces";
import { Button } from "@/components/ui/button";
import { isNumericFeedbackScoreValid } from "@/lib/traces";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { SelectItem } from "@/components/ui/select";
import { DropdownOption } from "@/types/shared";
import { cn, updateTextAreaHeight } from "@/lib/utils";
import { Textarea } from "@/components/ui/textarea";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import {
  categoryOptionLabelRenderer,
  findValueByAuthor,
  hasValuesByAuthor,
} from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";
import { UpdateFeedbackScoreData } from "./types";
import { useLoggedInUserNameOrOpenSourceDefaultUser } from "@/store/AppStore";

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
  const [editReason, setEditReason] = useState(false);

  useEffect(() => {
    setCategoryName(feedbackScoreData.category_name);
    setEditReason(false);
  }, [feedbackScoreData.category_name]);

  const [value, setValue] = useState<number | "">(feedbackScoreData.value);
  useEffect(() => {
    setValue(feedbackScoreData.value);

    if (!feedbackScoreData.value) {
      setEditReason(false);
      setReasonValue(undefined);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackScoreData.value]);

  const handleChangeValue = useCallback(
    (value: number, categoryName?: string) => {
      onUpdateFeedbackScore({
        categoryName,
        name,
        value,
      });
    },
    [name, onUpdateFeedbackScore],
  );

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

  const onChangeTextAreaTriggered = useCallback(() => {
    updateTextAreaHeight(textAreaRef.current);
  }, []);

  const {
    value: reasonValue,
    onChange: onReasonChange,
    onReset: onReasonReset,
    setInputValue: setReasonValue,
  } = useDebouncedValue({
    initialValue: feedbackScoreData.reason,
    onDebouncedChange: handleChangeReason,
    delay: SET_VALUE_DEBOUNCE_DELAY,
    onChange: onChangeTextAreaTriggered,
  });

  const deleteFeedbackScore = useCallback(() => {
    onDeleteFeedbackScore(name);
    setEditReason(false);
    setReasonValue(undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, setReasonValue]);

  const toggleEditReasonHandler = useCallback(() => {
    setEditReason(!editReason);
    setReasonValue(feedbackScoreData?.reason);
  }, [editReason, feedbackScoreData?.reason, setReasonValue]);

  const handleCopyReasonClick = async (v: string) => {
    await copy(v);

    toast({
      description: "Reason successfully copied to clipboard",
    });
  };

  const renderOptions = (feedbackDefinition: FeedbackDefinition) => {
    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      return (
        <DebounceInput
          className="my-0.5 h-7 min-w-[100px] py-1"
          max={feedbackDefinition.details.max}
          min={feedbackDefinition.details.min}
          step="any"
          dimension="sm"
          delay={SET_VALUE_DEBOUNCE_DELAY}
          onValueChange={(value) => {
            const newValue = value === "" ? "" : Number(value);

            setValue(newValue);

            if (newValue === "") {
              deleteFeedbackScore();
              return;
            }

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

    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      const onBooleanValueChange = (value?: string) => {
        const boolValue =
          value === feedbackDefinition.details.true_label ? 1 : 0;
        const categoryName = value;

        setCategoryName(categoryName);
        setValue(boolValue);
        handleChangeValue(boolValue, categoryName);
      };

      return (
        <ToggleGroup
          className="min-w-fit p-0.5"
          onValueChange={onBooleanValueChange}
          variant="outline"
          type="single"
          size="md"
          value={categoryName}
        >
          <ToggleGroupItem
            className="w-full"
            key="true"
            value={feedbackDefinition.details.true_label}
          >
            <div className="text-nowrap">
              {feedbackDefinition.details.true_label}
            </div>
          </ToggleGroupItem>
          <ToggleGroupItem
            className="w-full"
            key="false"
            value={feedbackDefinition.details.false_label}
          >
            <div className="text-nowrap">
              {feedbackDefinition.details.false_label}
            </div>
          </ToggleGroupItem>
        </ToggleGroup>
      );
    }

    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      const onCategoricalValueChange = (value?: string) => {
        if (value === "") {
          deleteFeedbackScore();
          return;
        }

        const categoryEntry = Object.entries(
          feedbackDefinition.details.categories,
        ).find(([categoryName]) => categoryName === value);

        if (categoryEntry) {
          const categoryValue = categoryEntry[1];

          setCategoryName(value);
          setValue(categoryValue);
          handleChangeValue(categoryValue, value);
        }
      };
      const categoricalOptionList = sortBy(
        Object.entries(feedbackDefinition.details.categories).map(
          ([name, value]) => ({
            name,
            value,
          }),
        ),
        "value",
      );

      const hasLongNames = categoricalOptionList.some((item) => {
        const label = categoryOptionLabelRenderer(item.name, item.value);
        return label.length > 10;
      });
      const hasMultipleOptions = categoricalOptionList.length > 2;

      if (hasLongNames || hasMultipleOptions) {
        const categoricalSelectOptionList = categoricalOptionList.map(
          (item) => ({
            label: item.name,
            value: item.name,
            description: String(item.value),
          }),
        );
        return (
          <SelectBox
            value={categoryName || ""}
            options={categoricalSelectOptionList}
            onChange={onCategoricalValueChange}
            className="my-0.5 h-7 min-w-[100px] py-1"
            renderTrigger={(value) => {
              const selectedOption = categoricalOptionList.find(
                (item) => item.name.trim() === value.trim(),
              );

              if (!selectedOption) {
                return <div className="truncate">Select a category</div>;
              }

              return (
                <span className="text-nowrap">
                  {categoryOptionLabelRenderer(value, selectedOption.value)}
                </span>
              );
            }}
            renderOption={(option: DropdownOption<string>) => (
              <SelectItem key={option.value} value={option.value}>
                {categoryOptionLabelRenderer(option.value, option.description)}
              </SelectItem>
            )}
          ></SelectBox>
        );
      }
      return (
        <ToggleGroup
          className="min-w-fit p-0.5"
          onValueChange={onCategoricalValueChange}
          variant="outline"
          type="single"
          size="md"
          value={String(categoryName)}
        >
          {categoricalOptionList.map(({ name, value }) => {
            return (
              <ToggleGroupItem className="w-full" key={name} value={name}>
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
      <div className="flex items-center overflow-hidden border-t border-border p-1 pl-0">
        <ColoredTagNew label={name} />
      </div>
      <div
        className="flex items-center overflow-hidden border-t border-border p-1"
        data-test-value={name}
      >
        {feedbackDefinition ? (
          <div className="min-w-0 flex-1 overflow-auto">
            {renderOptions(feedbackDefinition)}
          </div>
        ) : (
          <div>{feedbackScoreData?.value}</div>
        )}
      </div>
      <div className="flex items-center justify-center overflow-hidden border-t border-border">
        {feedbackScoreData?.value !== "" && (
          <Button
            variant="outline"
            size="icon-xs"
            className={cn(
              "relative group/reason-btn",
              editReason &&
                "bg-toggle-outline-active active:bg-toggle-outline-active hover:bg-toggle-outline-active",
            )}
            onClick={toggleEditReasonHandler}
          >
            {!!feedbackScoreData?.reason && (
              <div
                className={cn(
                  "absolute right-1 top-1 size-[8px] rounded-full border-2 border-white bg-primary group-hover/reason-btn:border-primary-foreground",
                  editReason &&
                    "border-toggle-outline-active group-hover/reason-btn:border-toggle-outline-active",
                )}
              />
            )}
            <MessageSquareMore />
          </Button>
        )}
      </div>
      <div className="flex items-center overflow-hidden border-t border-border px-0.5">
        {feedbackScoreData?.value !== "" && (
          <Button
            variant="minimal"
            size="icon-xs"
            onClick={deleteFeedbackScore}
          >
            <X />
          </Button>
        )}
      </div>

      {editReason && (
        <>
          <div className="group/reason-field relative col-span-3 px-1 pb-1 ">
            <Textarea
              placeholder="Add a reason..."
              value={reasonValue}
              onChange={onReasonChange}
              className="min-h-6 resize-none overflow-hidden py-1 pt-[4px]"
              ref={(e) => {
                textAreaRef.current = e;
                updateTextAreaHeight(e, 32);
              }}
            />
            {feedbackScoreData?.reason && (
              <div className="absolute right-2 top-1 hidden gap-1 group-hover/reason-field:flex">
                <TooltipWrapper content="Copy">
                  <Button
                    size="icon-2xs"
                    variant="outline"
                    onClick={() =>
                      handleCopyReasonClick(feedbackScoreData.reason!)
                    }
                  >
                    <Copy />
                  </Button>
                </TooltipWrapper>

                <TooltipWrapper content="Clear">
                  <Button
                    variant="outline"
                    size="icon-2xs"
                    onClick={onReasonReset}
                  >
                    <Trash />
                  </Button>
                </TooltipWrapper>
              </div>
            )}
          </div>
          <div></div>
        </>
      )}
    </>
  );
};

export default AnnotateRow;
