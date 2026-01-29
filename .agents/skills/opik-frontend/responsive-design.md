# Responsive Design

## When to Add Phone Support

Phone support is **NOT required by default**. Only add when:
- Explicitly requested in Jira ticket
- Working on onboarding features
- Component already has phone support

## Decision Framework

| Scenario | Use |
|----------|-----|
| Styling (padding, margins, colors) | Tailwind `md:` |
| Layout direction | Tailwind `md:flex-row` |
| Show/hide element | `hidden md:block` |
| Different component | `useIsPhone` |
| Different prop values | `useIsPhone` |
| Structural DOM changes | `useIsPhone` |

## Tailwind (Preferred)

Mobile-first: base classes = phone, `md:` = tablet+

```tsx
// Styling changes
<div className="w-full px-4 md:w-[468px] md:px-0">

// Layout direction
<div className="flex flex-col gap-4 md:flex-row md:gap-6">

// Visibility
<div className="hidden md:block">Desktop only</div>
```

## useIsPhone (When CSS Can't)

```tsx
import { useIsPhone } from "@/hooks/useIsPhone";

const { isPhonePortrait } = useIsPhone();

// Different component
if (isPhonePortrait) {
  return <BottomSheet>{content}</BottomSheet>;
}
return <SideDialog>{content}</SideDialog>;

// Different props
<DialogContent
  side={isPhonePortrait ? "bottom" : "right"}
  size={isPhonePortrait ? "full" : "md"}
/>
```

## Hooks Reference

```tsx
// Device detection
const { isPhone, isPhonePortrait, isPhoneLandscape } = useIsPhone();

// Custom queries
const isTablet = useMediaQuery("(min-width: 768px) and (max-width: 1023px)");

// Constants
import { QUERY_IS_PHONE_PORTRAIT } from "@/constants/responsiveness";
```

## Breakpoints

| Prefix | Min Width |
|--------|-----------|
| (none) | 0px (mobile) |
| `md:` | 768px |
| `lg:` | 1024px |
| `xl:` | 1280px |
