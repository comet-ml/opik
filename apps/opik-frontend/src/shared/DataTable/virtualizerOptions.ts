import { observeElementOffset, Virtualizer } from "@tanstack/react-virtual";

export const observeOwnAxisOffset = <
  TScroll extends Element,
  TItem extends Element,
>(
  instance: Virtualizer<TScroll, TItem>,
  cb: (offset: number, isScrolling: boolean) => void,
) => {
  let lastOffset: number | undefined;

  return observeElementOffset(instance, (offset, isScrolling) => {
    if (offset === lastOffset && isScrolling) return;
    lastOffset = offset;
    cb(offset, isScrolling);
  });
};
