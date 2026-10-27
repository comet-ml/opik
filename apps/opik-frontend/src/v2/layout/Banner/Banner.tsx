import React, {
  CSSProperties,
  ReactNode,
  useLayoutEffect,
  useRef,
} from "react";
import { X } from "lucide-react";

import { cn } from "@/lib/utils";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

export const BANNER_HEIGHT = 32;

interface BannerProps {
  visible: boolean;
  onChangeHeight: (height: number) => void;
  children: ReactNode;
  icon?: ReactNode;
  onDismiss?: () => void;
  dismissLabel?: string;
  ariaLabel?: string;
  className?: string;
  contentClassName?: string;
  style?: CSSProperties;
  initialHeight?: number;
}

const Banner: React.FC<BannerProps> = ({
  visible,
  onChangeHeight,
  children,
  icon,
  onDismiss,
  dismissLabel = "Dismiss",
  ariaLabel,
  className,
  contentClassName,
  style,
  initialHeight = BANNER_HEIGHT,
}) => {
  const heightRef = useRef(initialHeight);

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  // Before paint, with the known height: the layout animates its offset, so
  // publishing a measured height a frame late reads as the page sliding down.
  useLayoutEffect(() => {
    onChangeHeight(visible ? heightRef.current : 0);
  }, [visible, onChangeHeight]);

  if (!visible) {
    return null;
  }

  return (
    <div
      ref={ref}
      role="region"
      aria-label={ariaLabel}
      className={cn(
        "z-10 flex min-h-8 items-center gap-1.5 bg-primary px-2 py-1 text-white",
        className,
      )}
      style={style}
    >
      <div
        className={cn(
          "flex min-w-0 flex-1 items-center justify-center gap-1.5 text-center",
          contentClassName ?? "comet-body-xs",
        )}
      >
        {icon}
        {children}
      </div>
      {onDismiss && (
        <TooltipWrapper content={dismissLabel}>
          <Button
            variant="ghost"
            size="icon-2xs"
            aria-label={dismissLabel}
            onClick={onDismiss}
            className="shrink-0 text-inherit hover:bg-black/5 hover:text-inherit focus-visible:ring-current dark:text-inherit dark:hover:text-inherit"
          >
            <X />
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default Banner;
