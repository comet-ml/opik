import V1_TOKENS_CSS from "./v1/tokens.css?raw";
import V1_BASE_CSS from "./v1/base.css?raw";
import V1_CORE_CSS from "./v1/core.css?raw";
import V1_CORE_JS from "./v1/core.js?raw";
import V1_REVIEW_CSS from "./v1/packs/review.css?raw";
import V1_REVIEW_JS from "./v1/packs/review.js?raw";

export type KitPack = "review";

type Kit = {
  css: string[];
  js: string[];
  packs: Record<KitPack, { css: string; js: string }>;
};

export const LATEST_KIT_VERSION = 1;

const TRUSTED_HTML_POLICY = `const kitHtml = (() => {
  const policy = window.trustedTypes?.createPolicy("kit", { createHTML: (value) => value });
  return (value) => (policy ? policy.createHTML(value) : value);
})();`;

const KITS: Record<number, Kit> = {
  1: {
    css: [V1_TOKENS_CSS, V1_BASE_CSS, V1_CORE_CSS],
    js: [V1_CORE_JS],
    packs: { review: { css: V1_REVIEW_CSS, js: V1_REVIEW_JS } },
  },
};

export const loadKit = (version: number, packs: KitPack[]) => {
  const kit = KITS[version] ?? KITS[LATEST_KIT_VERSION];
  return {
    css: [...kit.css, ...packs.map((pack) => kit.packs[pack].css)].join("\n"),
    js: `(() => {\n${TRUSTED_HTML_POLICY}\n${[
      ...kit.js,
      ...packs.map((pack) => kit.packs[pack].js),
    ].join("\n;\n")}\n})();`,
  };
};
