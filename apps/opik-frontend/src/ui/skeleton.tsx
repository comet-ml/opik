import { cn } from "@/lib/utils";

function Skeleton({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "animate-shimmer rounded-md bg-muted bg-[linear-gradient(90deg,hsl(var(--muted))_0%,hsl(var(--muted))_40%,var(--skeleton-shimmer-highlight)_50%,hsl(var(--muted))_60%,hsl(var(--muted))_100%)] bg-[length:200%_100%]",
        className,
      )}
      {...props}
    />
  );
}

export { Skeleton };
