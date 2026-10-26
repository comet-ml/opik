/* global kit, kitHtml */
Object.assign(
  window.kit,
  (() => {
    const { h, isPresent, format, dl } = kit;

    const chip = (label, value, variant) =>
      isPresent(value)
        ? h(
            "span",
            { class: `kit-chip${variant ? ` kit-${variant}` : ""}` },
            label ? h("span", { class: "kit-chip-label", text: label }) : null,
            format(value),
          )
        : null;

    const stars = (count, max = 5) => {
      const value = Math.round(Number(count) || 0);
      return h(
        "span",
        { class: "kit-stars", title: `${count} / ${max}` },
        "★".repeat(Math.max(0, Math.min(max, value))),
        h("span", {
          class: "kit-off",
          text: "★".repeat(Math.max(0, max - value)),
        }),
      );
    };

    const CLAMP_HEIGHT = 320;
    const CLAMP_TOLERANCE = 40;

    const clampGroup = () => {
      const members = [];
      const buttons = [];
      let expanded = false;
      const sync = () => {
        members.forEach((el) => el.classList.remove("kit-clamp"));
        const overflowing = members.some(
          (el) => el.scrollHeight > CLAMP_HEIGHT + CLAMP_TOLERANCE,
        );
        members.forEach((el) =>
          el.classList.toggle("kit-clamp", overflowing && !expanded),
        );
        buttons.forEach((button) => {
          button.textContent = expanded ? "Collapse" : "Expand";
          button.hidden = !overflowing;
        });
      };
      const register = (el) => {
        members.push(el);
        el.addEventListener("kit-resize", sync);
        requestAnimationFrame(sync);
        return el;
      };
      return {
        text(value, className = "kit-text") {
          return register(h("div", { class: className, text: value }));
        },
        node(child) {
          return register(h("div", {}, child));
        },
        button() {
          const button = h("button", {
            class: "kit-link",
            type: "button",
            hidden: true,
            onclick: () => {
              expanded = !expanded;
              sync();
            },
          });
          buttons.push(button);
          return button;
        },
        refresh: () => requestAnimationFrame(sync),
      };
    };

    const segmented = (options, value, onChange) =>
      h(
        "span",
        { class: "kit-segmented", role: "group" },
        options.map(([key, label]) =>
          h("button", {
            type: "button",
            "aria-pressed": String(key === value),
            text: label,
            onclick: () => key !== value && onChange(key),
          }),
        ),
      );

    const emailFrame = (html) => {
      const frame = h("iframe", {
        class: "kit-email",
        sandbox: "allow-same-origin",
        title: "Email preview",
        srcdoc: kitHtml(html),
      });
      const fit = () => {
        const doc = frame.contentDocument;
        if (!doc?.documentElement) return;
        frame.style.height = "0px";
        frame.style.height = `${doc.documentElement.scrollHeight}px`;
        frame.dispatchEvent(new Event("kit-resize", { bubbles: true }));
      };
      frame.addEventListener("load", () => {
        fit();
        new ResizeObserver(fit).observe(frame);
      });
      return frame;
    };

    const verdict = (value, labels = ["Fail", "Pass"]) => {
      if (!isPresent(value)) return kit.tag("No verdict");
      const passed = Number(value) >= 1;
      return kit.tag(
        passed ? labels[1] : labels[0],
        passed ? "success" : "danger",
      );
    };

    const reasonView = (reason) => {
      if (!isPresent(reason))
        return h("span", { class: "kit-subtle kit-xs", text: "No rationale" });
      const lines = String(reason)
        .split("\n")
        .filter((line) => line.trim());
      const pairs = lines.map((line) =>
        /^\s*([A-Za-z_][\w .-]{0,40}?)\s*[:=]\s*(.*)$/.exec(line),
      );
      if (
        pairs.length > 1 &&
        pairs.filter(Boolean).length / pairs.length >= 0.6
      ) {
        return dl(
          lines.map((line, index) =>
            pairs[index]
              ? [
                  pairs[index][1].replace(/_/g, " ").toLowerCase(),
                  pairs[index][2] || "—",
                ]
              : ["", line],
          ),
        );
      }
      return h("div", { class: "kit-text kit-xs", text: String(reason) });
    };

    const revealGate = ({ summary, note, render, visible }) => {
      const wrap = h("div", {});
      const show = () => {
        wrap.replaceChildren(render(hide));
      };
      const hide = () => {
        wrap.replaceChildren(
          h(
            "div",
            { class: "kit-note" },
            h(
              "span",
              {},
              h("span", { class: "kit-strong", text: summary }),
              note ? ` — ${note}` : "",
            ),
            h("button", { type: "button", text: "Reveal", onclick: show }),
          ),
        );
      };
      visible ? show() : hide();
      return wrap;
    };

    const turn = ({ role, title, body }) => {
      const isUser = role === "user";
      return h(
        "div",
        {
          class: `kit-turn ${isUser ? "kit-turn-user" : "kit-turn-assistant"}`,
        },
        title ? h("div", { class: "kit-turn-title" }, title) : null,
        h("div", { class: "kit-turn-body" }, body),
      );
    };

    return {
      turn,
      chip,
      stars,
      clampGroup,
      segmented,
      emailFrame,
      verdict,
      reasonView,
      revealGate,
    };
  })(),
);
