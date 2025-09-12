import { cn } from "@/lib/utils";
import { TABLE_HEADER_Z_INDEX } from "@/constants/shared";

interface StickyHeaderProps
  extends React.HTMLAttributes<HTMLTableSectionElement> {
  stickyOffset?: number;
}

export function StickyHeader({
  className,
  stickyOffset = 0,
  ...props
}: StickyHeaderProps) {
  return (
    <thead
      className={cn(
        "sticky bg-background border-b",
        "transition-shadow",
        className,
      )}
      style={{
        top: `${stickyOffset}px`,
        zIndex: TABLE_HEADER_Z_INDEX,
      }}
      {...props}
    />
  );
}
