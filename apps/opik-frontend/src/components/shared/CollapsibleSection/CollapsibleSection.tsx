import * as React from "react";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  Accordion,
  AccordionItem,
  AccordionContent,
  CustomAccordionTrigger,
} from "@/components/ui/accordion";
import * as AccordionPrimitive from "@radix-ui/react-accordion";

// ============================================================================
// TYPES
// ============================================================================

export type CollapsibleSectionContainerProps = React.ComponentPropsWithoutRef<
  typeof AccordionPrimitive.Root
>;

export interface CollapsibleSectionRootProps {
  value: string;
  children: React.ReactNode;
  className?: string;
}

export interface CollapsibleSectionHeaderProps {
  leftContent?: React.ReactNode;
  rightContent?: React.ReactNode;
  className?: string;
}

export interface CollapsibleSectionContentProps {
  children: React.ReactNode;
  className?: string;
}

// ============================================================================
// COMPONENTS
// ============================================================================

/**
 * CollapsibleSection - A compound component for collapsible sections with
 * consistent styling across the application.
 *
 * Used by PrettyLLMMessage and Level2Container widgets.
 *
 * Components:
 * - CollapsibleSection.Container: Accordion wrapper (for multiple items)
 * - CollapsibleSection.Root: AccordionItem wrapper (for single collapsible)
 * - CollapsibleSection.Header: Trigger with chevron and flexible slots
 * - CollapsibleSection.Content: Content area with left border
 */

const CollapsibleSectionContainer: React.FC<
  CollapsibleSectionContainerProps
> = ({ children, className, ...props }) => {
  return (
    <Accordion
      className={cn("group/collapsible-section", className)}
      {...props}
    >
      {children}
    </Accordion>
  );
};

const CollapsibleSectionRoot: React.FC<CollapsibleSectionRootProps> = ({
  value,
  children,
  className,
}) => {
  return (
    <AccordionItem value={value} className={cn("border-none", className)}>
      {children}
    </AccordionItem>
  );
};

const CollapsibleSectionHeader: React.FC<CollapsibleSectionHeaderProps> = ({
  leftContent,
  rightContent,
  className,
}) => {
  return (
    <CustomAccordionTrigger
      className={cn(
        "flex items-center justify-between gap-1 rounded-sm py-1 pl-0 pr-1 transition-colors hover:bg-primary-foreground [&[data-state=open]_.collapsible-chevron]:rotate-90",
        className,
      )}
    >
      <div className="flex items-center gap-1">
        {/* eslint-disable-next-line tailwindcss/no-custom-classname */}
        <ChevronRight className="collapsible-chevron size-3.5 text-light-slate transition-transform duration-200" />
        {leftContent}
      </div>
      {rightContent}
    </CustomAccordionTrigger>
  );
};

const CollapsibleSectionContent: React.FC<CollapsibleSectionContentProps> = ({
  children,
  className,
}) => {
  return (
    <AccordionContent
      className={cn(
        "ml-[6px] pt-1 pb-2 px-0 border-l pl-[12px] space-y-2",
        className,
      )}
    >
      {children}
    </AccordionContent>
  );
};

// ============================================================================
// EXPORTS
// ============================================================================

export const CollapsibleSection = {
  Container: CollapsibleSectionContainer,
  Root: CollapsibleSectionRoot,
  Header: CollapsibleSectionHeader,
  Content: CollapsibleSectionContent,
};

export {
  CollapsibleSectionContainer,
  CollapsibleSectionRoot,
  CollapsibleSectionHeader,
  CollapsibleSectionContent,
};

export default CollapsibleSection;
