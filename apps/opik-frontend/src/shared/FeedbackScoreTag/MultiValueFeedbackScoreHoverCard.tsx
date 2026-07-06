import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { FeedbackScoreValueByAuthorMap } from "@/types/traces";
import ColorIndicator from "@/shared/ColorIndicator/ColorIndicator";
import React from "react";
import { Loader2 } from "lucide-react";
import isNumber from "lodash/isNumber";
import { Separator } from "@/ui/separator";

import {
  getCategoricFeedbackScoreValuesMap,
  getIsCategoricFeedbackScore,
} from "./utils";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useFeedbackScoreSourceLabel from "@/hooks/useFeedbackScoreSourceLabel";

const Header = ({ color, label }: { color: string; label: string }) => {
  return (
    <div className="flex h-6 items-center gap-1.5 pl-1 pr-2">
      <ColorIndicator label={label} color={color} variant="square" />
      <div className="comet-body-xs-accented truncate text-foreground">
        {label}
      </div>
    </div>
  );
};

const AuthorLabel = ({
  mapKey,
  entry,
}: {
  mapKey: string;
  entry: FeedbackScoreValueByAuthorMap[string];
}) => {
  const { isLoading, label } = useFeedbackScoreSourceLabel(
    entry.source_queue_id,
    entry.source,
  );

  const authorName = entry.author ?? mapKey;

  return (
    <div className="flex min-w-0 flex-1 items-center justify-between gap-2">
      <span className="comet-body-xs truncate text-muted-slate">
        {authorName}
      </span>
      <span className="comet-body-xs shrink-0 text-light-slate">
        {isLoading ? (
          <Loader2 className="size-3.5 animate-spin text-light-slate" />
        ) : (
          label
        )}
      </span>
    </div>
  );
};

const NumericScoreContent = ({
  color,
  valueByAuthor,
  label,
  value,
}: {
  color: string;
  valueByAuthor: FeedbackScoreValueByAuthorMap;
  label: string;
  value: number | string;
}) => {
  return (
    <div className="flex flex-col gap-1">
      <Header color={color} label={label} />
      <Separator />
      <div className="flex flex-col">
        {Object.entries(valueByAuthor).map(([key, entry]) => (
          <div key={key} className="flex h-6 items-center gap-1.5 px-2">
            <AuthorLabel mapKey={key} entry={entry} />
            <TooltipWrapper
              content={isNumber(entry.value) ? String(entry.value) : undefined}
            >
              <span className="comet-body-xs-accented text-foreground">
                {formatScoreDisplay(entry.value)}
              </span>
            </TooltipWrapper>
          </div>
        ))}
      </div>
      {Object.keys(valueByAuthor).length > 1 && (
        <>
          <Separator />
          <div className="flex h-6 items-center gap-1.5 px-2">
            <span className="comet-body-xs min-w-0 flex-1 truncate text-muted-slate">
              Average
            </span>
            <TooltipWrapper
              content={isNumber(value) ? String(value) : undefined}
            >
              <span className="comet-body-xs-accented text-foreground">
                {formatScoreDisplay(value)}
              </span>
            </TooltipWrapper>
          </div>
        </>
      )}
    </div>
  );
};

const CategoricalScoreContent = ({
  color,
  label,
  valueByAuthor,
}: {
  color: string;
  label: string;
  valueByAuthor: FeedbackScoreValueByAuthorMap;
}) => {
  const scoreValuesMap = getCategoricFeedbackScoreValuesMap(valueByAuthor);

  return (
    <div className="flex flex-col gap-1">
      <Header color={color} label={label} />
      <Separator />
      {Array.from(scoreValuesMap.entries()).map(
        ([category, { users, value }], index) => (
          <div key={category}>
            <div className="flex flex-col gap-1">
              <div className="flex h-6 items-center justify-between px-2">
                <span className="comet-body-xs-accented truncate text-foreground">
                  {value}
                </span>
                <span className="comet-body-xs-accented shrink-0 text-foreground">
                  {users.length} {users.length === 1 ? "user" : "users"}
                </span>
              </div>
              <div className="flex flex-col pl-2">
                {users.map((user) => (
                  <div key={user.mapKey} className="flex h-6 items-center">
                    <AuthorLabel mapKey={user.mapKey} entry={user.entry} />
                  </div>
                ))}
              </div>
            </div>
            {index < scoreValuesMap.size - 1 && <Separator className="my-1" />}
          </div>
        ),
      )}
    </div>
  );
};

type MultiValueFeedbackScoreHoverCardProps = {
  color: string;
  valueByAuthor?: FeedbackScoreValueByAuthorMap;
  label: string;
  value: number | string;
  category?: string;
  footer?: string;
  children: React.ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

const MultiValueFeedbackScoreHoverCard: React.FC<
  MultiValueFeedbackScoreHoverCardProps
> = ({
  color,
  valueByAuthor,
  label,
  value,
  category = "",
  footer,
  children,
  open,
  onOpenChange,
}) => {
  const isCategoricFeedbackScore = getIsCategoricFeedbackScore(category);
  const hasEntries =
    valueByAuthor &&
    typeof valueByAuthor === "object" &&
    Object.keys(valueByAuthor).length > 0;

  if (!hasEntries) {
    return children;
  }

  return (
    <HoverCard open={open} onOpenChange={onOpenChange}>
      <HoverCardTrigger asChild>{children}</HoverCardTrigger>
      <HoverCardContent
        side="top"
        align="start"
        alignOffset={-4}
        className="w-[200px] border border-border p-1.5"
        collisionPadding={24}
      >
        {isCategoricFeedbackScore ? (
          <CategoricalScoreContent
            color={color}
            label={label}
            valueByAuthor={valueByAuthor}
          />
        ) : (
          <NumericScoreContent
            color={color}
            valueByAuthor={valueByAuthor}
            label={label}
            value={value}
          />
        )}
        {footer && (
          <>
            <Separator />
            <div className="px-2 py-1">
              <span className="comet-body-xs text-light-slate">{footer}</span>
            </div>
          </>
        )}
      </HoverCardContent>
    </HoverCard>
  );
};

export default MultiValueFeedbackScoreHoverCard;
