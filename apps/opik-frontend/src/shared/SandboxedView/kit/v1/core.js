/* global opik, kitHtml */
window.kit = (() => {
  const h = (tag, props = {}, ...children) => {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === undefined || value === null || value === false) continue;
      if (key === "class" || key === "className") el.className = value;
      else if (key === "text") el.textContent = value;
      else if (key === "style") el.style.cssText = value;
      else if (key.startsWith("on"))
        el.addEventListener(key.slice(2).toLowerCase(), value);
      else el.setAttribute(key, value === true ? "" : value);
    }
    for (const child of children.flat(Infinity)) {
      if (child === undefined || child === null || child === false) continue;
      el.append(
        child instanceof Node ? child : document.createTextNode(String(child)),
      );
    }
    return el;
  };

  const isObject = (value) =>
    value !== null && typeof value === "object" && !Array.isArray(value);

  const tryJson = (value) => {
    if (typeof value !== "string") return value;
    const text = value.trim();
    if (!text.startsWith("{") && !text.startsWith("[")) return value;
    try {
      return JSON.parse(text);
    } catch {
      return value;
    }
  };

  const get = (source, path) =>
    path.reduce(
      (node, key) =>
        node === undefined || node === null ? undefined : tryJson(node)[key],
      source,
    );

  const isPresent = (value) =>
    value !== undefined &&
    value !== null &&
    !(typeof value === "string" && !value.trim());

  const first = (...values) => values.find(isPresent);

  const asText = (value) => {
    if (!isPresent(value)) return "";
    if (typeof value === "string") return value;
    return JSON.stringify(value, null, 2);
  };

  const looksLikeHtml = (value) =>
    typeof value === "string" &&
    /<(html|body|table|div|p|br|span)\b/i.test(value);

  const stripHtml = (value) => {
    const doc = new DOMParser().parseFromString(kitHtml(value), "text/html");
    doc
      .querySelectorAll("style, script, head")
      .forEach((node) => node.remove());
    return (doc.body.innerText || doc.body.textContent || "")
      .replace(/\n{3,}/g, "\n\n")
      .trim();
  };

  const wordCount = (text) =>
    text.trim() ? text.trim().split(/\s+/).length : 0;

  const format = (value) =>
    typeof value === "number" ? value.toLocaleString() : String(value);

  const tag = (text, variant) =>
    isPresent(text)
      ? h("span", {
          class: `kit-tag${variant ? ` kit-${variant}` : ""}`,
          text: format(text),
        })
      : null;

  const card = ({ title, extra, body, highlight, className }) =>
    h(
      "section",
      {
        class: `kit-card${highlight ? " kit-highlight" : ""}${
          className ? ` ${className}` : ""
        }`,
      },
      title || extra
        ? h(
            "div",
            { class: "kit-card-header" },
            title,
            h("span", { class: "kit-spacer" }),
            extra,
          )
        : null,
      h("div", { class: "kit-card-body" }, body),
    );

  const dl = (entries) => {
    const rows = entries.filter(([, value]) => isPresent(value));
    return rows.length
      ? h(
          "dl",
          { class: "kit-dl" },
          rows.map(([label, value]) => [
            h("dt", { text: label }),
            h("dd", {}, value instanceof Node ? value : format(value)),
          ]),
        )
      : null;
  };

  const json = (value) => h("pre", { text: asText(value) });

  const fallbackView = (data, error) =>
    h(
      "div",
      { class: "kit-stack" },
      h("div", {
        class: "kit-note",
        text: error
          ? `This item has an unexpected format (${
              error.message ?? error
            }). Showing the raw data.`
          : "This item has an unexpected format. Showing the raw data.",
      }),
      json(data),
    );

  const action = (name, payload) => opik.action(name, payload);

  let refresh = () => {};

  const mount = (render) => {
    const root = h("div", {});
    document.body.append(root);
    opik.onData((data) => {
      refresh = () => {
        let view;
        try {
          view = render(data);
        } catch (error) {
          root.replaceChildren(fallbackView(data, error));
          opik.error(error?.message ?? error);
          return;
        }
        if (!view) {
          root.replaceChildren(fallbackView(data));
          opik.error("The custom view returned nothing for this item.");
          return;
        }
        root.replaceChildren(view);
        opik.rendered();
      };
      refresh();
    });
  };

  return {
    h,
    isObject,
    tryJson,
    get,
    isPresent,
    first,
    asText,
    looksLikeHtml,
    stripHtml,
    wordCount,
    format,
    tag,
    card,
    dl,
    json,
    mount,
    action,
    refresh: () => refresh(),
  };
})();
