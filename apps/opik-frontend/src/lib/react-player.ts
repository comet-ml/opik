import ReactPlayerModule from "react-player/lazy";

// react-player 2 ships CommonJS marked `__esModule`. Vite 8 gives a `"type": "module"`
// importer the whole `module.exports` as the default import, not its `default` export.
const ReactPlayer =
  (ReactPlayerModule as unknown as { default?: typeof ReactPlayerModule })
    .default ?? ReactPlayerModule;

export default ReactPlayer;
