import opik

client = opik.Opik()

trace = client.trace(
    name="trace-name",
)
span1 = trace.span(name="span-1")
span2 = span1.span(name="span-2")

span2.end()
span1.end()
trace.end()

span1.log_feedback_score(name="toxicity", value=0.0, reason="Too many bad words")
client.log_spans_feedback_scores(
    [
        {"id": span2.id, "name": "toxicity", "value": 0.5},
        {
            "id": span2.id,
            "name": "truthfullness",
            "value": 1.0,
            "reason": "some good reason",
        },
    ]
)
client.end()
