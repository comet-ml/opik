import React, {
  createContext,
  useContext,
  ReactNode,
  useCallback,
  useEffect,
} from "react";
import * as Popover from "@radix-ui/react-popover";
import { cn } from "@/lib/utils";
import { tooltipVariants } from "@/components/ui/tooltip";

const SHOW_DELAY = 500;
const HIDE_DELAY = 100;

interface PopoverState {
  content: ReactNode | null;
  triggerRef: React.RefObject<HTMLElement> | null;
}

interface TablePopoverContextValue {
  showPopover: (
    content: ReactNode,
    triggerRef: React.RefObject<HTMLElement>,
  ) => void;
  hidePopover: () => void;
}

const TablePopoverContext = createContext<TablePopoverContextValue>({
  showPopover: () => {},
  hidePopover: () => {},
});

export const useTablePopover = () => useContext(TablePopoverContext);

type DataTableTooltipContextProps = {
  children: ReactNode;
  delayDuration?: number;
};

const DataTableTooltipContext: React.FC<DataTableTooltipContextProps> = ({
  children,
  delayDuration = SHOW_DELAY,
}) => {
  const [popoverState, setPopoverState] = React.useState<PopoverState>({
    content: null,
    triggerRef: null,
  });
  const timeoutRef = React.useRef<NodeJS.Timeout>();
  const previousTriggerRef = React.useRef<HTMLElement | null>(null);
  const isMouseOverPopoverRef = React.useRef(false);
  const isMouseOverTriggerRef = React.useRef(false);

  const hidePopoverWithDelay = useCallback(() => {
    // Only hide if a mouse is not over either trigger or popover
    setTimeout(() => {
      if (!isMouseOverPopoverRef.current && !isMouseOverTriggerRef.current) {
        timeoutRef.current && clearTimeout(timeoutRef.current);
        setPopoverState({ content: null, triggerRef: null });
        previousTriggerRef.current = null;
      }
    }, HIDE_DELAY);
  }, []);

  const showPopover = useCallback(
    (content: ReactNode, triggerRef: React.RefObject<HTMLElement>) => {
      isMouseOverTriggerRef.current = true;
      // Check if it's the same trigger
      if (previousTriggerRef.current !== triggerRef.current) {
        timeoutRef.current && clearTimeout(timeoutRef.current);
        timeoutRef.current = setTimeout(() => {
          setPopoverState({ content, triggerRef });
        }, delayDuration);
      } else {
        setPopoverState({ content, triggerRef });
      }
      previousTriggerRef.current = triggerRef.current;
    },
    [delayDuration],
  );

  const hidePopover = useCallback(() => {
    isMouseOverTriggerRef.current = false;
    hidePopoverWithDelay();
  }, [hidePopoverWithDelay]);

  const handlePopoverMouseEnter = useCallback(() => {
    isMouseOverPopoverRef.current = true;
  }, []);

  const handlePopoverMouseLeave = useCallback(() => {
    isMouseOverPopoverRef.current = false;
    hidePopoverWithDelay();
  }, [hidePopoverWithDelay]);

  useEffect(
    () => () => timeoutRef.current && clearTimeout(timeoutRef.current),
    [],
  );

  const open = Boolean(popoverState.content && popoverState.triggerRef);

  return (
    <TablePopoverContext.Provider value={{ showPopover, hidePopover }}>
      {children}
      <Popover.Root open={open}>
        {popoverState.triggerRef?.current && (
          <Popover.Anchor virtualRef={popoverState.triggerRef} />
        )}
        <Popover.Portal>
          <Popover.Content
            className={cn(
              tooltipVariants({
                variant: "default",
              }),
            )}
            sideOffset={4}
            collisionPadding={16}
            onMouseEnter={handlePopoverMouseEnter}
            onMouseLeave={handlePopoverMouseLeave}
            align="center"
            side="top"
            autoFocus={false}
          >
            {popoverState.content}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    </TablePopoverContext.Provider>
  );
};

export default DataTableTooltipContext;
