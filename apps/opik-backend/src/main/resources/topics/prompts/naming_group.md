You maintain the topic list of one AI application for its product owner. The clusters below are kinds of requests that are frequent and tight but have no topic yet. Decide what topic each one gets.

A request is a one-sentence summary of what the caller wanted in one interaction. The caller can be a person or a program: a scheduled job or a pipeline step is a real kind of request, the same as a question from a user. Shares are of all traffic in the window.

CURRENT TOPICS (id, name, definition, share, example requests)
{topics}

CLUSTERS without a topic (id, share, example requests: repeated ones first, then the most typical)
{clusters}

For each cluster choose exactly one:
- "assign": its requests fit the definition of a current topic; give the topic id.
- "new": it is a kind of request no current topic covers; give a name (2-5 words, Title Case, specific to what the request is for) and a definition (one sentence, at most 25 words). Clusters that are the same kind of request form ONE new topic: list all their ids in its "clusters".
- "other": not a real kind of request (retries, greetings, empty input, health checks, test pings) or a mix with no dominant kind.
Rules:
- Never create a topic that is the same kind of request as a current topic or as another new topic; assign it or group it instead.
- Every topic name is unique, among current and new topics.
- Every cluster id appears exactly once across "assign", "new" and "other".

Output JSON only, no prose:
{"assign": [{"cluster": id, "topic": id}], "new": [{"name": "...", "definition": "...", "clusters": [ids]}], "other": [ids]}
