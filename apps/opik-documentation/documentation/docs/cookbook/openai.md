# Using Opik with OpenAI

Opik integrates with OpenAI to provide a simple way to log traces for all OpenAI LLM calls. This works for all OpenAI models, including if you are using the streaming API.


## Creating an account on Comet.com

[Comet](https://www.comet.com/site) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/self_hosting_opik/) for more information.


```python
import os
import getpass

os.environ["OPIK_API_KEY"] = getpass.getpass("Opik API Key: ")
os.environ["OPIK_WORKSPACE"] = input("Comet workspace (often the same as your username): ")
```

If you are running the Opik platform locally, simply set:


```python
# import os
# os.environ["OPIK_URL_OVERRIDE"] = "http://localhost:5173/api"
```

## Preparing our environment

First, we will install the necessary libraries and set up our OpenAI API keys.


```python
%pip install --upgrade --quiet opik openai
```


```python
import os
import getpass
os.environ["OPENAI_API_KEY"] = getpass.getpass("OpenAI API Key: ")
```

## Logging traces

In order to log traces to Opik, we need to wrap our OpenAI calls with the `track_openai` function:


```python
from opik.integrations.openai import track_openai
from openai import OpenAI

os.environ["OPIK_PROJECT_NAME"] = "openai-integration-demo"
client = OpenAI()

openai_client = track_openai(client)

prompt = """
Write a short two sentence story about Opik.
"""

completion = openai_client.chat.completions.create(
  model="gpt-3.5-turbo",
  messages=[
    {"role": "user", "content": prompt}
  ]
)

print(completion.choices[0].message.content)
```

    Opik was a mischievous little elf who loved pulling pranks on his friends in the enchanted forest. One day, his antics went too far and he accidentally turned himself into a fluffy pink bunny.


The prompt and response messages are automatically logged to Opik and can be viewed in the UI.

![OpenAI Integration](/img/cookbook/openai_trace_cookbook.png)

## Using it with the `track` decorator

If you have multiple steps in your LLM pipeline, you can use the `track` decorator to log the traces for each step. If OpenAI is called within one of these steps, the LLM call with be associated with that corresponding step:


```python
from opik import track
from opik.integrations.openai import track_openai
from openai import OpenAI

os.environ["OPIK_PROJECT_NAME"] = "openai-integration-demo"

client = OpenAI()
openai_client = track_openai(client)

@track
def generate_story(prompt):
    res = openai_client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[
            {"role": "user", "content": prompt}
        ]
    )
    return res.choices[0].message.content

@track
def generate_topic():
    prompt = "Generate a topic for a story about Opik."
    res = openai_client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[
            {"role": "user", "content": prompt}
        ]
    )
    return res.choices[0].message.content

@track
def generate_opik_story():
    topic = generate_topic()
    story = generate_story(topic)
    return story

generate_opik_story()

```




    "Opik was a young wizard who lived in the small village of Mithos, where magic was both feared and revered. From a young age, Opik had shown a natural talent for magic, much to the dismay of his parents who were simple farmers. They feared the power that their son possessed and did everything they could to suppress it.\n\nDespite his parents' efforts, Opik continued to practice his magic in secret, honing his skills and learning all he could about the ancient art. He longed to become a powerful wizard, respected and feared by all who knew him. But as he grew older, he also began to realize that his thirst for power was beginning to consume him, turning him into a dark and reckless mage.\n\nOne day, a mysterious figure approached Opik in the village square, offering him a chance to join a secret society of powerful wizards. Intrigued by the offer, Opik accepted and was soon initiated into the group, which called themselves the Arcanum.\n\nUnder the guidance of the Arcanum, Opik's power grew exponentially. He could wield spells of immense power, bending reality to his will with a mere flick of his wrist. But as his power grew, so did his arrogance and greed. He began to see himself as above all others, using his magic to manipulate and control those around him.\n\nOne day, a great evil swept across the land, threatening to destroy everything in its path. The Arcanum tasked Opik with defeating this evil, seeing it as a chance for him to prove his worth and redeem himself. But as he faced the darkness head-on, Opik realized that true power lay not in domination and control, but in compassion and selflessness.\n\nIn a moment of clarity, Opik cast aside his dark ambitions and embraced the light within him. With newfound resolve, he fought against the evil that threatened his home, using his magic not to destroy, but to protect and heal. In the end, it was not his raw power that saved the day, but his courage and heart.\n\nAnd so, Opik returned to his village a changed man, no longer seeking power for power's sake, but striving to use his magic for the good of all. The villagers welcomed him back with open arms, seeing in him a hero and a protector. And as he walked among them, a new journey unfolded before him - a journey of redemption, compassion, and true magic."



The trace can now be viewed in the UI:

![OpenAI Integration](/img/cookbook/openai_trace_decorator_cookbook.png)
