import React from "react";
import KeyboardBadge from "./KeyboardBadge";

type PopoverFooterProps = {
  expandWithArrowKeys: boolean;
};

const PopoverFooter: React.FC<PopoverFooterProps> = ({
  expandWithArrowKeys,
}) => (
  <div className="border-t px-4 py-3">
    <p className="comet-body-xs text-light-slate">
      Press <KeyboardBadge>Tab</KeyboardBadge> or{" "}
      <KeyboardBadge>{expandWithArrowKeys ? "←↑→↓" : "↑↓"}</KeyboardBadge> to
      navigate, <KeyboardBadge>Enter</KeyboardBadge> to select, and{" "}
      <KeyboardBadge>Esc</KeyboardBadge> to close.
    </p>
  </div>
);

export default PopoverFooter;
