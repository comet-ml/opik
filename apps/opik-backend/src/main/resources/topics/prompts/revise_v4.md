You maintain the topic list of one AI application for its product owner. Topics must stay stable from day to day: a topic keeps its id and name as long as it exists. Today some clusters of user requests are not covered by the current topics; decide what to do with them.

A request is a one-sentence summary of what the user wanted in one interaction. Shares are of all traffic in the last 30 days.

CURRENT TOPICS (id, name, definition, share, example requests)
{topics}

CANDIDATE CLUSTERS not covered by the current topics (id, share, example requests, most frequent first)
{candidates}

For each candidate cluster choose exactly one:
- "assign": it belongs to an existing topic (its requests fit that topic's definition).
- "new": it is a distinct kind of user request that deserves its own topic; give a name (2-5 words, Title Case, specific) and a definition (one sentence, at most 25 words). Several candidate clusters may form one new topic.
- "other": not a real user request (retries, greetings, empty, system events) or a mix with no dominant kind. A cluster with one dominant kind of real request and a share of 2% or more (overall or in the last 7 days) is never "other": assign it, or make it a new topic even if it is unrelated to the application's main purpose (users may start asking for something new).
Rules:
- The list may hold at most {max_topics} topics in total, current plus new. If that would be exceeded, do not create the new topic; use "assign" or "other".
- Create a new topic only when the cluster is clearly not covered by any current definition. Do not create near-duplicates of current topics.
- Every candidate id appears exactly once across "assign", "new" and "other".

Output JSON only, no prose:
{"assign": [{"cluster": id, "topic": id}], "new": [{"name": "...", "definition": "...", "clusters": [ids]}], "other": [ids]}
