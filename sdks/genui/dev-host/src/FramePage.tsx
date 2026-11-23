import { useCallback, useEffect, useState, type CSSProperties } from "react";
import { HtmlFrame } from "opik-genui/HtmlFrame";
import { TOKENS } from "opik-genui/tokens";

interface FrameProps {
  html: string;
  engineVersion: number;
}

declare global {
  interface Window {
    setFrame?: (props: FrameProps) => void;
  }
}

const DATA = { cost: [{ model: "gpt-4o", usd: 12.4 }] };

export function FramePage() {
  const [props, setProps] = useState<FrameProps>({
    html: "<p>ready</p>",
    engineVersion: 1,
  });
  const [errors, setErrors] = useState<string[]>([]);
  const onError = useCallback(
    (error: Error) => setErrors((list) => [...list, error.message]),
    [],
  );

  useEffect(() => {
    window.setFrame = (next) => {
      setErrors([]);
      setProps(next);
    };
  }, []);

  return (
    <div className="host" data-theme="light">
      <main className="host-main" data-testid="host-main">
        <div
          className="ov-view"
          data-theme="light"
          style={TOKENS.light as CSSProperties}
        >
          <HtmlFrame
            html={props.html}
            engineVersion={props.engineVersion}
            data={DATA}
            theme="light"
            onError={onError}
          />
        </div>
      </main>
      <aside className="host-side">
        <ul className="errors" data-testid="errors">
          {errors.map((message, i) => (
            <li key={i}>{message}</li>
          ))}
        </ul>
      </aside>
    </div>
  );
}
