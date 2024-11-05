import opik

opik_client = opik.Opik()

spans = opik_client.search_spans(
    project_name="Demo Project",
    filter_string='input contains "How many unique albums"',
)

print(spans)
