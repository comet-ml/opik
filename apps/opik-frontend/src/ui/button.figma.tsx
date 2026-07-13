import figma from "@figma/code-connect";

import { Button } from "./button";

figma.connect(
  Button,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=2370-129033",
  {
    props: {
      variant: figma.enum("Type", {
        Primary: "default",
        Secondary: "secondary",
        Tertiary: "outline",
        Ghost: "ghost",
        Destructive: "destructive",
      }),
      size: figma.enum("Size", {
        Large: "default",
        Medium: "sm",
        Small: "xs",
        XSmall: "2xs",
      }),
      disabled: figma.enum("State", {
        Disabled: true,
      }),
      iconLeft: figma.boolean("IconLeft", {
        true: figma.instance("↳ IconLeft-Instance"),
        false: undefined,
      }),
      iconRight: figma.boolean("IconRight", {
        true: figma.instance("↳ IconRight-Instance"),
        false: undefined,
      }),
      label: figma.textContent("Label"),
    },
    example: ({ variant, size, disabled, iconLeft, iconRight, label }) => (
      <Button variant={variant} size={size} disabled={disabled}>
        {iconLeft}
        {label}
        {iconRight}
      </Button>
    ),
  },
);

figma.connect(
  Button,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=6-1377",
  {
    props: {
      variant: figma.enum("Type", {
        Primary: "default",
        Secondary: "secondary",
        Tertiary: "outline",
        Ghost: "ghost",
        Minimal: "minimal",
      }),
      size: figma.enum("Size", {
        Large: "icon",
        Medium: "icon-sm",
        Small: "icon-xs",
        XSmall: "icon-2xs",
      }),
      disabled: figma.enum("State", {
        Disabled: true,
      }),
      badge: figma.boolean("Badge"),
      icon: figma.instance("Icon"),
    },
    example: ({ variant, size, disabled, badge, icon }) => (
      <Button variant={variant} size={size} badge={badge} disabled={disabled}>
        {icon}
      </Button>
    ),
  },
);

figma.connect(
  Button,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=3399-1942",
  {
    props: {
      size: figma.enum("Size", {
        Regular: "sm",
        Small: "xs",
        XSmall: "2xs",
      }),
      disabled: figma.enum("State", {
        Disabled: true,
      }),
      iconLeft: figma.boolean("IconLeft", {
        true: figma.instance("↳ IconLeft-Instance"),
        false: undefined,
      }),
      iconRight: figma.boolean("IconRight", {
        true: figma.instance("↳ IconRight-Instance"),
        false: undefined,
      }),
      label: figma.textContent("Label"),
    },
    example: ({ size, disabled, iconLeft, iconRight, label }) => (
      <Button variant="link" size={size} disabled={disabled}>
        {iconLeft}
        {label}
        {iconRight}
      </Button>
    ),
  },
);
