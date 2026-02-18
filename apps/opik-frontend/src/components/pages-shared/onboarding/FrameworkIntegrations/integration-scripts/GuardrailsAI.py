from guardrails import Guard, OnFailAction
from guardrails.hub import PolitenessCheck
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.guardrails import track_guardrails  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# Create a politeness check validator
politeness_check = PolitenessCheck(
    llm_callable="gpt-3.5-turbo", on_fail=OnFailAction.NOOP
)

# Create a Guard with the validator
guard: Guard = Guard().use(politeness_check)

# Track the Guard with Opik
guard = track_guardrails(  # HIGHLIGHTED_LINE
    guard,  # HIGHLIGHTED_LINE
    project_name="guardrails-integration-example",  # HIGHLIGHTED_LINE
)  # HIGHLIGHTED_LINE

# Validate some text - this will be logged to Opik
guard.validate("Would you be so kind to pass me a cup of tea?")

guard.validate("Shut your mouth up and give me the tea.")
