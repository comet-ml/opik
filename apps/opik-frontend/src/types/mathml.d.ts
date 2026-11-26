import type { HTMLAttributes } from "react";

type MathMLProps = HTMLAttributes<HTMLElement> & {
  display?: "block" | "inline";
};

// Browsers render MathML natively (MathML Core), but React's types only list
// HTML and SVG tags.
declare module "react" {
  namespace JSX {
    interface IntrinsicElements {
      math: MathMLProps;
      mrow: MathMLProps;
      mi: MathMLProps;
      mo: MathMLProps;
      mn: MathMLProps;
      mtext: MathMLProps;
      msub: MathMLProps;
      mfrac: MathMLProps;
    }
  }
}
