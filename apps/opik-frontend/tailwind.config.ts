import { fontFamily } from "tailwindcss/defaultTheme";

/** @type {import("tailwindcss").Config} */
module.exports = {
  darkMode: ["class"],
  content: [
    "./pages/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./app/**/*.{ts,tsx}",
    "./src/**/*.{ts,tsx}",
  ],
  prefix: "",
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: {
        "2xl": "1400px",
      },
    },
    extend: {
      fontFamily: {
        sans: ["Inter", ...fontFamily.sans],
        code: [
          `Ubuntu Mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`,
        ],
      },
      colors: {
        white: "var(--white)",

        /* Action cards */
        "action-trace-background": "var(--action-trace-background)",
        "action-trace-text": "var(--action-trace-text)",
        "action-experiment-background": "var(--action-experiment-background)",
        "action-experiment-text": "var(--action-experiment-text)",
        "action-guardrail-background": "var(--action-guardrail-background)",
        "action-guardrail-text": "var(--action-guardrail-text)",
        "action-playground-background": "var(--action-playground-background)",
        "action-playground-text": "var(--action-playground-text)",

        /* Legacy colors (for backward compatibility) */
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        "foreground-secondary": "hsl(var(--foreground-secondary))",
        warning: "hsl(var(--warning))",
        success: "hsl(var(--success))",
        "light-slate": "hsl(var(--light-slate))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
          hover: "hsl(var(--primary-hover))",
          active: "hsl(var(--primary-active))",
          100: "hsl(var(--primary-100))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          slate: "hsl(var(--muted-slate))",
          gray: "hsl(var(--muted-gray))",
          disabled: "hsl(var(--muted-disabled))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
          background: "hsl(var(--accent-background))",
        },
        popover: {
          gray: "hsl(var(--popover-gray))",
        },
        tooltip: {
          DEFAULT: "hsl(var(--tooltip))",
          foreground: "hsl(var(--tooltip-foreground))",
        },
        soft: {
          background: "hsl(var(--soft-background))",
        },
        slate: {
          300: "hsl(var(--slate-300))",
        },
        gray: {
          100: "var(--gray-100)",
        },

        /* Custom colors for simplified class names */
        "toggle-outline-active": "var(--toggle-outline-active)",
        "diff-removed-bg": "var(--diff-removed-bg)",
        "diff-removed-text": "var(--diff-removed-text)",
        "diff-added-bg": "var(--diff-added-bg)",
        "diff-added-text": "var(--diff-added-text)",
        "upload-icon-bg": "hsl(var(--upload-icon-bg))",
        "code-block": "var(--code-block)",
        "breadcrumb-last": "hsl(var(--breadcrumb-last))",
        "special-button": "var(--special-button)",
        "thread-active": "var(--thread-active)",

        /* Info box colors */
        "info-box-bg": "hsl(var(--info-box-bg))",
        "info-box-text": "hsl(var(--info-box-text))",
        "info-box-icon-bg": "hsl(var(--info-box-icon-bg))",
        "info-box-icon-text": "hsl(var(--info-box-icon-text))",

        /* Warning box colors */
        "warning-box-bg": "hsl(var(--warning-box-bg))",
        "warning-box-text": "hsl(var(--warning-box-text))",
        "warning-box-icon-bg": "hsl(var(--warning-box-icon-bg))",
        "warning-box-icon-text": "hsl(var(--warning-box-icon-text))",

        /* Template icon colors */
        "template-icon-metrics": "var(--template-icon-metrics)",
        "template-icon-performance": "var(--template-icon-performance)",
        "template-icon-scratch": "var(--template-icon-scratch)",
        "template-icon-experiments": "var(--template-icon-experiments)",
      },
      borderRadius: {
        xxl: "calc(var(--radius) + 4px)",
        xl: "calc(var(--radius) + 2px)",
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" },
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
      },
      boxShadow: {
        "action-card": "var(--action-card-shadow)",
      },
    },
  },
  plugins: [
    require("tailwindcss-animate"),
    require("@tailwindcss/typography"),
    function ({ addVariant, e }) {
      addVariant(
        "group-hover-except-self",
        ({ modifySelectors, separator }) => {
          modifySelectors(({ className }) => {
            return `.group:hover .${e(
              `group-hover-except-self${separator}${className}`,
            )}:not(:hover)`;
          });
        },
      );
    },
  ],
  safelist: ["playground-table", "comet-compare-optimizations-table"],
};
