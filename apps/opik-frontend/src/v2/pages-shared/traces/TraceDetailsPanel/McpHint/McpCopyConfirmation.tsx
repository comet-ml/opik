import React from "react";
import { Check } from "lucide-react";

type McpCopyConfirmationProps = {
  confirmation: string;
};

/**
 * What a copy leaves behind, in place of the routes it was taken from.
 *
 * Inline rather than taking the card over, so the description above it and the
 * docs link below stay put: the user has the clipboard they came for and the
 * card is about to get out of the way on its own.
 */
const McpCopyConfirmation: React.FunctionComponent<
  McpCopyConfirmationProps
> = ({ confirmation }) => (
  <div className="flex min-h-7 items-center gap-1.5 text-green-600">
    <Check className="size-3.5 shrink-0" />
    <span className="comet-body-xs leading-4">{confirmation}</span>
  </div>
);

export default McpCopyConfirmation;
