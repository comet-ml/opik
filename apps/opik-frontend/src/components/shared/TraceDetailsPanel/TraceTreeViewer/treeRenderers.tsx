import React from "react";
import isEmpty from "lodash/isEmpty";
import isNumber from "lodash/isNumber";
import isNull from "lodash/isNull";
import isUndefined from "lodash/isUndefined";
import { TreeRenderProps } from "react-complex-tree";
import { ChevronRight, Clock, Coins, Hash, PenLine } from "lucide-react";
import { cn, millisecondsToSeconds } from "@/lib/utils";
import { BASE_TRACE_DATA_TYPE } from "@/types/traces";
import BaseTraceDataTypeIcon from "../BaseTraceDataTypeIcon";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import styles from "./TraceTreeViewer.module.scss";
import { formatCost } from "@/lib/money";

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

    const duration =
      isNaN(props.item.data.duration) ||
      isUndefined(props.item.data.duration) ||
      isNull(props.item.data.duration)
        ? "NA"
        : `${millisecondsToSeconds(props.item.data.duration)}s`;

    const name = props.item.data.name || "NA";
    const tokens = props.item.data.tokens;
    const feedbackScores = props.item.data.feedback_scores;
    const estimatedCost = props.item.data.total_estimated_cost;

    const type = props.item.data.type as BASE_TRACE_DATA_TYPE;

    return (
      <li {...(props.context.itemContainerWithChildrenProps as object)}>
        <button
          className={cn(
            styles.chainItemOuterContainer,
            props.context.isFocused && styles.focused,
          )}
          {...(props.context.itemContainerWithoutChildrenProps as object)}
          onFocus={props.context.interactiveElementProps.onFocus}
        >
          <div className={cn(styles.chainItemContainer)}>
            <div className={styles.detailsContainerWithArrow}>
              {generateStubCells(props.depth)}
              {props.arrow}
              <div className={styles.detailsOuterContainer}>
                <div className={styles.detailsInnerContainer}>
                  <BaseTraceDataTypeIcon type={type} />
                  <TooltipWrapper content={name}>
                    <p> {name} </p>
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
