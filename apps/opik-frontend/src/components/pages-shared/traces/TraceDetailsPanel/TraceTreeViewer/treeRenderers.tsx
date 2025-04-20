import React from "react";
import isEmpty from "lodash/isEmpty";
import isNumber from "lodash/isNumber";
import isUndefined from "lodash/isUndefined";
import { TreeRenderProps } from "react-complex-tree";
import {
  ChevronRight,
  CircleAlert,
  Clock,
  Coins,
  Hash,
  MessageSquareMore,
  PenLine,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import { BASE_TRACE_DATA_TYPE } from "@/types/traces";
import BaseTraceDataTypeIcon from "../BaseTraceDataTypeIcon";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import styles from "./TraceTreeViewer.module.scss";
import { GuardrailResult } from "@/types/guardrails";

const generateStubCells = (depth: number) => {
  const items = [];
  for (let i = 0; i <= depth; i++) {
    items.push(
      <div
        key={i}
        className={cn(styles.stubCell, {
          [styles.first]: i === 0,
          [styles.last]: i === depth,
        })}
      />,
    );
  }
  return items;
};

export const treeRenderers: TreeRenderProps = {
  renderTreeContainer: (props) => (
    <div className={styles.chainsContainer}>
      <ul {...props.containerProps}>{props.children}</ul>
    </div>
  ),

  renderItemsContainer: (props) => (
    // eslint-disable-next-line tailwindcss/no-custom-classname
    <ul className={`level-${props.depth}`} {...props.containerProps}>
      {props.children}
    </ul>
  ),

  renderItem: (props) => {
    const widthPercentage = Math.min(
      (props.item.data.duration / props.item.data.maxDuration) * 100,
      100,
    );

    const offset =
      props.item.data.startTimestamp - props.item.data.maxStartTime;
    const offsetPercentage = Math.max(
      (offset / props.item.data.maxDuration) * 100,
      0,
    );

    const duration = formatDuration(props.item.data.duration);

    const name = props.item.data.name || "NA";
    const tokens = props.item.data.tokens;
    const feedbackScores = props.item.data.feedback_scores;
    const comments = props.item.data.comments;
    const estimatedCost = props.item.data.total_estimated_cost;
    const guardrailStatus = props.item.data.output?.guardrail_result ?? null;

    const type = props.item.data.type as BASE_TRACE_DATA_TYPE;

    return (
      <li {...(props.context.itemContainerWithChildrenProps as object)}>
        <button
          className={cn(
            styles.chainItemOuterContainer,
            props.context.isFocused && styles.focused,
          )}
          {...(props.context.itemContainerWithoutChildrenProps as object)}
          onClick={() => {
            if (props.context.interactiveElementProps.onFocus) {
              props.context.focusItem();
            }
          }}
          onFocus={props.context.interactiveElementProps.onFocus}
        >
          <div
            className={cn(
              styles.chainItemContainer,
              props.item.data.isInSearch && styles.search,
            )}
          >
            <div className={styles.detailsContainerWithArrow}>
              {generateStubCells(props.depth)}
              {props.arrow}
              <div className={styles.detailsOuterContainer}>
                <div className={styles.detailsInnerContainer}>
                  <BaseTraceDataTypeIcon type={type} />
                  <TooltipWrapper content={name}>
                    <p className={cn(props.context.isFocused && "font-medium")}>
                      {name}
                    </p>
                  </TooltipWrapper>
                  <div className={styles.detailsDivider} />
                </div>
              </div>
            </div>
            <div className={styles.chainSpanOuterContainer}>
              <div className={styles.chainSpanContainer}>
                <div className={styles.chainItemSpanDivider} />
                <div
                  className={styles.chainSpanWrapper}
                  style={{
                    background: props.item.data.spanColor,
                    width: widthPercentage + "%",
                    left: offsetPercentage + "%",
                  }}
                />
              </div>
              <div className={styles.chainSpanDetails}>
                {props.item.data.hasError && (
                  <TooltipWrapper content="Has error">
                    <div className={styles.chainSpanDetailsItem}>
                      <CircleAlert className="text-destructive" />
                    </div>
                  </TooltipWrapper>
                )}
                {Boolean(guardrailStatus !== null) && (
                  <TooltipWrapper
                    content={
                      guardrailStatus === GuardrailResult.PASSED
                        ? "Guardrails passed"
                        : "Guardrails failed"
                    }
                  >
                    <div className={styles.chainSpanDetailsItem}>
                      <div
                        className={cn("size-2 rounded-full shrink-0 mt-0.5", {
                          "bg-emerald-500":
                            guardrailStatus === GuardrailResult.PASSED,
                          "bg-rose-500":
                            guardrailStatus === GuardrailResult.FAILED,
                        })}
                      ></div>
                    </div>
                  </TooltipWrapper>
                )}
                <TooltipWrapper content="Duration in seconds">
                  <div className={styles.chainSpanDetailsItem}>
                    <Clock /> {duration}
                  </div>
                </TooltipWrapper>
                {isNumber(tokens) && (
                  <TooltipWrapper content="Total amount of tokens">
                    <div className={styles.chainSpanDetailsItem}>
                      <Hash /> {tokens}
                    </div>
                  </TooltipWrapper>
                )}
                {Boolean(feedbackScores?.length) && (
                  <TooltipWrapper content="Number of feedback scores">
                    <div className={styles.chainSpanDetailsItem}>
                      <PenLine /> {feedbackScores.length}
                    </div>
                  </TooltipWrapper>
                )}
                {Boolean(comments?.length) && (
                  <TooltipWrapper content="Number of comments">
                    <div className={styles.chainSpanDetailsItem}>
                      <MessageSquareMore /> {comments.length}
                    </div>
                  </TooltipWrapper>
                )}
                {!isUndefined(estimatedCost) && (
                  <TooltipWrapper
                    content={`Estimated cost ${formatCost(estimatedCost)}`}
                  >
                    <div className={styles.chainSpanDetailsItem}>
                      <Coins /> {formatCost(estimatedCost, true)}
                    </div>
                  </TooltipWrapper>
                )}
              </div>
            </div>
          </div>
        </button>

        {props.children}
      </li>
    );
  },

  renderItemArrow: (props) => {
    return (
      <div
        className={cn(
          styles.chainNodeArrowContainer,
          isEmpty(props.item.children) && styles.empty,
          props.context.isExpanded && styles.expended,
        )}
        {...(props.context.arrowProps as object)}
        {...(props.context.interactiveElementProps as object)}
      >
        <ChevronRight className="size-3" />
      </div>
    );
  },

  renderDepthOffset: 1,
};
