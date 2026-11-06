You maintain the topic list of one AI application for its product owner. The clusters below are kinds of user requests that are frequent and tight but have no topic yet. Each was checked against the current topics and does not belong to any of them. Give them new topics.

A request is a one-sentence summary of what the user wanted in one interaction. Shares are of all traffic in the last 30 days.

CURRENT TOPICS (id, name, definition, share, example requests)
{topics}

CLUSTERS without a topic (id, share, example requests, most frequent first)
{clusters}

For each cluster choose exactly one:
- "new": give a name (2-5 words, Title Case, specific to what the user wants) and a definition (one sentence, at most 25 words). Clusters that are the same kind of request form ONE new topic: list all their ids in its "clusters".
- "other": not a real user request (retries, greetings, empty, system events) or a mix with no dominant kind.
Rules:
- Never create two new topics for the same kind of request; group them instead. A new topic must also differ from every current topic.
- Every topic name is unique, among current and new topics.
- Every cluster id appears exactly once across "new" and "other".

Output JSON only, no prose:
{"new": [{"name": "...", "definition": "...", "clusters": [ids]}], "other": [ids]}
