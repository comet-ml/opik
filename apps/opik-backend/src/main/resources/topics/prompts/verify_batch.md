You check decisions in the topic list of an AI application. A request is a one-sentence summary of what a user wanted.
Each PAIR below shows an existing TOPIC (name, definition, typical requests) and a NEW CLUSTER of requests that was proposed
to be counted under that topic.

For every pair answer: is the new cluster the same kind of request as the topic, so that the product owner would count these
requests under this topic? Answer false if the cluster is about a different subject or a different goal, even when the
wording looks similar. Judge each pair on its own.

{pairs}

Output JSON only: {"answers": [{"pair": <number>, "same": true|false}, ...]} with every pair exactly once.
