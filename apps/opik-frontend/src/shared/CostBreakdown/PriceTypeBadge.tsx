import React from "react";
import { describePriceType, PriceTypeTag } from "@/lib/model-prices";
import { Tag, TagProps } from "@/ui/tag";
import { TYPE_TAG_VARIANT } from "./format";

const variantOf = (tag: PriceTypeTag): TagProps["variant"] => {
  if (tag.kind === "catalog") return TYPE_TAG_VARIANT[tag.type];
  if (tag.kind === "alias") return TYPE_TAG_VARIANT.custom;
  return "default";
};

type PriceTypeBadgeProps = {
  tag?: PriceTypeTag;
};

const PriceTypeBadge: React.FC<PriceTypeBadgeProps> = ({ tag }) => {
  if (!tag) return null;
  return (
    <Tag variant={variantOf(tag)} size="sm" className="max-w-40 shrink-0">
      {describePriceType(tag)}
    </Tag>
  );
};

export default PriceTypeBadge;
