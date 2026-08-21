import { Virtualizer } from "@tanstack/react-virtual";

type ScrollAxis = "scrollTop" | "scrollLeft";

// The page body scroll container scrolls both axes and TanStack's default
// observer fires on every scroll event, so a sideways scroll would recompute the
// row window and a vertical one the column window
const observeAxisOffset =
  (axis: ScrollAxis) =>
  <TScroll extends Element, TItem extends Element>(
    instance: Virtualizer<TScroll, TItem>,
    cb: (offset: number, isScrolling: boolean) => void,
  ) => {
    const element = instance.scrollElement;
    if (!element) return;

    let lastOffset = element[axis];
    cb(lastOffset, false);

    const onScroll = () => {
      const offset = element[axis];
      if (offset === lastOffset) return;
      lastOffset = offset;
      cb(offset, true);
    };
    const onScrollEnd = () => cb(element[axis], false);

    element.addEventListener("scroll", onScroll, { passive: true });
    element.addEventListener("scrollend", onScrollEnd, { passive: true });

    return () => {
      element.removeEventListener("scroll", onScroll);
      element.removeEventListener("scrollend", onScrollEnd);
    };
  };

export const observeVerticalOffset = observeAxisOffset("scrollTop");
export const observeHorizontalOffset = observeAxisOffset("scrollLeft");
