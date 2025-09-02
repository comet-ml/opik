import { cva } from "class-variance-authority";

export const rootStyleVariants = cva("group flex max-w-full items-start ", {
  variants: {
    size: {
      default: "gap-2 py-3",
      sm: "gap-1 py-2",
    },
  },
  defaultVariants: {
    size: "default",
  },
});

export const textStyleVariants = cva("whitespace-pre-line break-words", {
  variants: {
    size: {
      default: "comet-body-s pr-8",
      sm: "comet-body-xs",
    },
  },
  defaultVariants: {
    size: "default",
  },
});

export const usernameStyleVariants = cva("truncate leading-none", {
  variants: {
    size: {
      default: "comet-body-s-accented pt-[2px]",
      sm: "comet-body-xs-accented pt-px",
    },
  },
  defaultVariants: {
    size: "default",
  },
});

export const createdAtStyleVariants = cva(
  "shrink-0 leading-none text-light-slate",
  {
    variants: {
      size: {
        default: "comet-body-xs pt-1",
        sm: "comet-body-xs pt-0.5 text-[10px]",
      },
    },
    defaultVariants: {
      size: "default",
    },
  },
);
