# Opik Exa Integration

Seamlessly integrate [Opik](https://www.comet.com/docs/opik/) observability with [Exa](https://exa.ai/) search calls.

## Installation

```bash
npm install opik-exa exa-js
```

## Usage

```typescript
import Exa from "exa-js";
import { trackExa } from "opik-exa";

const exa = new Exa(process.env.EXA_API_KEY);
const trackedExa = trackExa(exa, {
  traceMetadata: {
    tags: ["search", "exa"],
  },
});

const result = await trackedExa.search("latest AI eval papers");
await trackedExa.flush();
```

## Tracing Contract

Tracked Exa methods are logged as `tool` spans:

- `search`
- `search_and_contents` / `searchAndContents`
- `find_similar` / `findSimilar`
- `get_contents` / `getContents`
- `answer`

Each span includes:

- `opik.kind=search`
- `opik.provider=exa`
- `opik.operation=<method>`
