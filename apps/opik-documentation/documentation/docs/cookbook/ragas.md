# Using Ragas to evaluate RAG pipelines

In this notebook, we will showcase how to use Opik with Ragas for monitoring and evaluation of RAG (Retrieval-Augmented Generation) pipelines.

There are two main ways to use Opik with Ragas:

1. Using Ragas metrics to score traces
2. Using the Ragas `evaluate` function to score a dataset

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=ragas&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=ragas&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=ragas&utm_campaign=opik) for more information.


```python
%pip install --quiet --upgrade opik ragas nltk
```

    
    [1m[[0m[34;49mnotice[0m[1;39;49m][0m[39;49m A new release of pip is available: [0m[31;49m24.2[0m[39;49m -> [0m[32;49m24.3.1[0m
    [1m[[0m[34;49mnotice[0m[1;39;49m][0m[39;49m To update, run: [0m[32;49mpip install --upgrade pip[0m
    Note: you may need to restart the kernel to use updated packages.



```python
import opik

opik.configure(use_local=False)
```

    OPIK: Your Opik API key is available in your account settings, can be found at https://www.comet.com/api/my/settings/ for Opik cloud
    OPIK: Configuration saved to file: /Users/jacquesverre/.opik.config


## Preparing our environment

First, we will configure the OpenAI API key.


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

## Integrating Opik with Ragas

### Using Ragas metrics to score traces

Ragas provides a set of metrics that can be used to evaluate the quality of a RAG pipeline, including but not limited to: `answer_relevancy`, `answer_similarity`, `answer_correctness`, `context_precision`, `context_recall`, `context_entity_recall`, `summarization_score`. You can find a full list of metrics in the [Ragas documentation](https://docs.ragas.io/en/latest/references/metrics.html#).

These metrics can be computed on the fly and logged to traces or spans in Opik. For this example, we will start by creating a simple RAG pipeline and then scoring it using the `answer_relevancy` metric.

#### Create the Ragas metric

In order to use the Ragas metric without using the `evaluate` function, you need to initialize the metric with a `RunConfig` object and an LLM provider. For this example, we will use LangChain as the LLM provider with the Opik tracer enabled.

We will first start by initializing the Ragas metric:


```python
# Import the metric
from ragas.metrics import AnswerRelevancy

# Import some additional dependencies
from langchain_openai.chat_models import ChatOpenAI
from langchain_openai.embeddings import OpenAIEmbeddings
from ragas.llms import LangchainLLMWrapper
from ragas.embeddings import LangchainEmbeddingsWrapper

# Initialize the Ragas metric
llm = LangchainLLMWrapper(ChatOpenAI())
emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings())

answer_relevancy_metric = AnswerRelevancy(llm=llm, embeddings=emb)
```

Once the metric is initialized, you can use it to score a sample question. Given that the metric scoring is done asynchronously, you need to use the `asyncio` library to run the scoring function.


```python
# Run this cell first if you are running this in a Jupyter notebook
import nest_asyncio

nest_asyncio.apply()
```


```python
import asyncio
from ragas.integrations.opik import OpikTracer
from ragas.dataset_schema import SingleTurnSample
import os

os.environ["OPIK_PROJECT_NAME"] = "ragas-integration"


# Define the scoring function
def compute_metric(metric, row):
    row = SingleTurnSample(**row)

    opik_tracer = OpikTracer(tags=["ragas"])

    async def get_score(opik_tracer, metric, row):
        score = await metric.single_turn_ascore(row, callbacks=[opik_tracer])
        return score

    # Run the async function using the current event loop
    loop = asyncio.get_event_loop()

    result = loop.run_until_complete(get_score(opik_tracer, metric, row))
    return result


# Score a simple example
row = {
    "user_input": "What is the capital of France?",
    "response": "Paris",
    "retrieved_contexts": ["Paris is the capital of France.", "Paris is in France."],
}

score = compute_metric(answer_relevancy_metric, row)
print("Answer Relevancy score:", score)
```

    OPIK: Started logging traces to the "ragas-integration" project at https://www.comet.com/opik/jacques-comet/redirect/projects?name=ragas-integration.


    Answer Relevancy score: 0.9999999999999996


If you now navigate to Opik, you will be able to see that a new trace has been created in the `Default Project` project.

#### Score traces

You can score traces by using the `update_current_trace` function.

The advantage of this approach is that the scoring span is added to the trace allowing for a more fine-grained analysis of the RAG pipeline. It will however run the Ragas metric calculation synchronously and so might not be suitable for production use-cases.


```python
from opik import track, opik_context


@track
def retrieve_contexts(question):
    # Define the retrieval function, in this case we will hard code the contexts
    return ["Paris is the capital of France.", "Paris is in France."]


@track
def answer_question(question, contexts):
    # Define the answer function, in this case we will hard code the answer
    return "Paris"


@track(name="Compute Ragas metric score", capture_input=False)
def compute_rag_score(answer_relevancy_metric, question, answer, contexts):
    # Define the score function
    row = {"user_input": question, "response": answer, "retrieved_contexts": contexts}
    score = compute_metric(answer_relevancy_metric, row)
    return score


@track
def rag_pipeline(question):
    # Define the pipeline
    contexts = retrieve_contexts(question)
    answer = answer_question(question, contexts)

    score = compute_rag_score(answer_relevancy_metric, question, answer, contexts)
    opik_context.update_current_trace(
        feedback_scores=[{"name": "answer_relevancy", "value": round(score, 4)}]
    )

    return answer


rag_pipeline("What is the capital of France?")
```

    OPIK: Started logging traces to the "ragas-integration" project at https://www.comet.com/opik/jacques-comet/redirect/projects?name=ragas-integration.





    'Paris'



#### Evaluating datasets using the Opik `evaluate` function

You can use Ragas metrics with the Opik `evaluate` function. This will compute the metrics on all the rows of the dataset and return a summary of the results.

As Ragas metrics are only async, we will need to create a wrapper to be able to use them with the Opik `evaluate` function.


```python
from datasets import load_dataset
from ragas.dataset_schema import SingleTurnSample
from opik.evaluation.metrics import base_metric, score_result
import opik


opik_client = opik.Opik()

# Create a small dataset
fiqa_eval = load_dataset("explodinggradients/fiqa", "ragas_eval")

# Reformat the dataset to match the schema expected by the Ragas evaluate function
hf_dataset = fiqa_eval["baseline"].select(range(3))
dataset_items = hf_dataset.map(
    lambda x: {
        "user_input": x["question"],
        "reference": x["ground_truths"][0],
        "retrieved_contexts": x["contexts"],
    }
)
dataset = opik_client.get_or_create_dataset("ragas-demo-dataset")
dataset.insert(dataset_items)

# Create an evaluation task
def evaluation_task(x):
    return {
        "user_input": x["question"],
        "response": x["answer"],
        "retrieved_contexts": x["contexts"],
    }

# Create scoring metric wrapper
class AnswerRelevancyWrapper(base_metric.BaseMetric):
    def __init__(self, metric):
        self.name = "answer_relevancy_metric"
        self.metric = metric
    
    async def get_score(self, row):
        row = SingleTurnSample(**row)
        score = await self.metric.single_turn_ascore(row)
        return score
    
    def score(self, user_input, response, **ignored_kwargs):
        # Run the async function using the current event loop
        loop = asyncio.get_event_loop()

        result = loop.run_until_complete(self.get_score(row))
        
        return score_result.ScoreResult(
            value=result,
            name=self.name
        )

scoring_metric = AnswerRelevancyWrapper(answer_relevancy_metric)
opik.evaluation.evaluate(
    dataset,
    evaluation_task,
    scoring_metrics=[scoring_metric],
)
```

    Evaluation: 100%|██████████| 3/3 [00:04<00:00,  1.39s/it]



<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">╭─ ragas-demo-dataset (3 samples) ──────╮
│                                       │
│ <span style="font-weight: bold">Total time:       </span> 00:00:04           │
│ <span style="font-weight: bold">Number of samples:</span> 3                  │
│                                       │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">answer_relevancy_metric: 1.0000 (avg)</span> │
│                                       │
╰───────────────────────────────────────╯
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">Uploading results to Opik <span style="color: #808000; text-decoration-color: #808000">...</span> 
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">View the results <a href="https://www.comet.com/opik/jacques-comet/experiments/01936935-83db-72a4-9f81-c1d07efdb433/compare?experiments=%5B%2206747336-1ba1-735d-8000-1c80bcbbd902%22%5D" target="_blank">in your Opik dashboard</a>.
</pre>






    EvaluationResult(experiment_id='06747336-1ba1-735d-8000-1c80bcbbd902', experiment_name=None, test_results=[TestResult(test_case=TestCase(trace_id='06747336-26a1-7f3a-8000-b2bfee0a458f', dataset_item_id='06745f1a-1e67-7579-8000-2a216721898a', scoring_inputs={'reference': "Have the check reissued to the proper payee.Just have the associate sign the back and then deposit it.  It's called a third party cheque and is perfectly legal.  I wouldn't be surprised if it has a longer hold period and, as always, you don't get the money if the cheque doesn't clear. Now, you may have problems if it's a large amount or you're not very well known at the bank.  In that case you can have the associate go to the bank and endorse it in front of the teller with some ID.  You don't even technically have to be there.  Anybody can deposit money to your account if they have the account number. He could also just deposit it in his account and write a cheque to the business.", 'question': 'How to deposit a cheque issued to an associate in my business into my business account?', 'answer': '\nThe best way to deposit a cheque issued to an associate in your business into your business account is to open a business account with the bank. You will need a state-issued "dba" certificate from the county clerk\'s office as well as an Employer ID Number (EIN) issued by the IRS. Once you have opened the business account, you can have the associate sign the back of the cheque and deposit it into the business account.', 'user_input': 'How to deposit a cheque issued to an associate in my business into my business account?', 'ground_truths': ["Have the check reissued to the proper payee.Just have the associate sign the back and then deposit it.  It's called a third party cheque and is perfectly legal.  I wouldn't be surprised if it has a longer hold period and, as always, you don't get the money if the cheque doesn't clear. Now, you may have problems if it's a large amount or you're not very well known at the bank.  In that case you can have the associate go to the bank and endorse it in front of the teller with some ID.  You don't even technically have to be there.  Anybody can deposit money to your account if they have the account number. He could also just deposit it in his account and write a cheque to the business."], 'contexts': ['Just have the associate sign the back and then deposit it.  It\'s called a third party cheque and is perfectly legal.  I wouldn\'t be surprised if it has a longer hold period and, as always, you don\'t get the money if the cheque doesn\'t clear. Now, you may have problems if it\'s a large amount or you\'re not very well known at the bank.  In that case you can have the associate go to the bank and endorse it in front of the teller with some ID.  You don\'t even technically have to be there.  Anybody can deposit money to your account if they have the account number. He could also just deposit it in his account and write a cheque to the business."I have checked with Bank of America, and they say the ONLY way to cash (or deposit, or otherwise get access to the funds represented by a check made out to my business) is to open a business account. They tell me this is a Federal regulation, and every bank will say the same thing.  To do this, I need a state-issued ""dba"" certificate (from the county clerk\'s office) as well as an Employer ID Number (EIN) issued by the IRS. AND their CHEAPEST business banking account costs $15 / month. I think I can go to the bank that the check is drawn upon, and they will cash it, assuming I have documentation showing that I am the sole proprietor. But I\'m not sure.... What a racket!!"When a business asks me to make out a cheque to a person rather than the business name, I take that as a red flag. Frankly it usually means that the person doesn\'t want the money going through their business account for some reason - probably tax evasion. I\'m not saying you are doing that, but it is a frequent issue. If the company makes the cheque out to a person they may run the risk of being party to fraud. Worse still they only have your word for it that you actually own the company, and aren\'t ripping off your employer by pocketing their payment. Even worse, when the company is audited and finds that cheque, the person who wrote it will have to justify and document why they made it out to you or risk being charged with embezzlement. It\'s very much in their interests to make the cheque out to the company they did business with. Given that, you should really have an account in the name of your business. It\'s going to make your life much simpler in the long run.'], 'retrieved_contexts': ['Just have the associate sign the back and then deposit it.  It\'s called a third party cheque and is perfectly legal.  I wouldn\'t be surprised if it has a longer hold period and, as always, you don\'t get the money if the cheque doesn\'t clear. Now, you may have problems if it\'s a large amount or you\'re not very well known at the bank.  In that case you can have the associate go to the bank and endorse it in front of the teller with some ID.  You don\'t even technically have to be there.  Anybody can deposit money to your account if they have the account number. He could also just deposit it in his account and write a cheque to the business."I have checked with Bank of America, and they say the ONLY way to cash (or deposit, or otherwise get access to the funds represented by a check made out to my business) is to open a business account. They tell me this is a Federal regulation, and every bank will say the same thing.  To do this, I need a state-issued ""dba"" certificate (from the county clerk\'s office) as well as an Employer ID Number (EIN) issued by the IRS. AND their CHEAPEST business banking account costs $15 / month. I think I can go to the bank that the check is drawn upon, and they will cash it, assuming I have documentation showing that I am the sole proprietor. But I\'m not sure.... What a racket!!"When a business asks me to make out a cheque to a person rather than the business name, I take that as a red flag. Frankly it usually means that the person doesn\'t want the money going through their business account for some reason - probably tax evasion. I\'m not saying you are doing that, but it is a frequent issue. If the company makes the cheque out to a person they may run the risk of being party to fraud. Worse still they only have your word for it that you actually own the company, and aren\'t ripping off your employer by pocketing their payment. Even worse, when the company is audited and finds that cheque, the person who wrote it will have to justify and document why they made it out to you or risk being charged with embezzlement. It\'s very much in their interests to make the cheque out to the company they did business with. Given that, you should really have an account in the name of your business. It\'s going to make your life much simpler in the long run.'], 'response': '\nThe best way to deposit a cheque issued to an associate in your business into your business account is to open a business account with the bank. You will need a state-issued "dba" certificate from the county clerk\'s office as well as an Employer ID Number (EIN) issued by the IRS. Once you have opened the business account, you can have the associate sign the back of the cheque and deposit it into the business account.'}, task_output={'user_input': 'How to deposit a cheque issued to an associate in my business into my business account?', 'response': '\nThe best way to deposit a cheque issued to an associate in your business into your business account is to open a business account with the bank. You will need a state-issued "dba" certificate from the county clerk\'s office as well as an Employer ID Number (EIN) issued by the IRS. Once you have opened the business account, you can have the associate sign the back of the cheque and deposit it into the business account.', 'retrieved_contexts': ['Just have the associate sign the back and then deposit it.  It\'s called a third party cheque and is perfectly legal.  I wouldn\'t be surprised if it has a longer hold period and, as always, you don\'t get the money if the cheque doesn\'t clear. Now, you may have problems if it\'s a large amount or you\'re not very well known at the bank.  In that case you can have the associate go to the bank and endorse it in front of the teller with some ID.  You don\'t even technically have to be there.  Anybody can deposit money to your account if they have the account number. He could also just deposit it in his account and write a cheque to the business."I have checked with Bank of America, and they say the ONLY way to cash (or deposit, or otherwise get access to the funds represented by a check made out to my business) is to open a business account. They tell me this is a Federal regulation, and every bank will say the same thing.  To do this, I need a state-issued ""dba"" certificate (from the county clerk\'s office) as well as an Employer ID Number (EIN) issued by the IRS. AND their CHEAPEST business banking account costs $15 / month. I think I can go to the bank that the check is drawn upon, and they will cash it, assuming I have documentation showing that I am the sole proprietor. But I\'m not sure.... What a racket!!"When a business asks me to make out a cheque to a person rather than the business name, I take that as a red flag. Frankly it usually means that the person doesn\'t want the money going through their business account for some reason - probably tax evasion. I\'m not saying you are doing that, but it is a frequent issue. If the company makes the cheque out to a person they may run the risk of being party to fraud. Worse still they only have your word for it that you actually own the company, and aren\'t ripping off your employer by pocketing their payment. Even worse, when the company is audited and finds that cheque, the person who wrote it will have to justify and document why they made it out to you or risk being charged with embezzlement. It\'s very much in their interests to make the cheque out to the company they did business with. Given that, you should really have an account in the name of your business. It\'s going to make your life much simpler in the long run.']}), score_results=[ScoreResult(name='answer_relevancy_metric', value=0.9999999999999996, reason=None, metadata=None, scoring_failed=False)]), TestResult(test_case=TestCase(trace_id='06747336-269d-72d0-8000-5de5633d4759', dataset_item_id='06745f1a-1e67-7a95-8000-13c09bccb2f4', scoring_inputs={'reference': "Sure you can.  You can fill in whatever you want in the From section of a money order, so your business name and address would be fine. The price only includes the money order itself.  You can hand deliver it yourself if you want, but if you want to mail it, you'll have to provide an envelope and a stamp. Note that, since you won't have a bank record of this payment, you'll want to make sure you keep other records, such as the stub of the money order.  You should probably also ask the contractor to give you a receipt.", 'question': 'Can I send a money order from USPS as a business?', 'answer': '\nYes, you can send a money order from USPS as a business. You can fill in whatever you want in the From section of the money order, including your business name and address. The price only includes the money order itself, so you will need to provide an envelope and a stamp if you want to mail it. It is important to keep records of the payment, such as the stub of the money order, and to ask the contractor for a receipt.', 'user_input': 'Can I send a money order from USPS as a business?', 'ground_truths': ["Sure you can.  You can fill in whatever you want in the From section of a money order, so your business name and address would be fine. The price only includes the money order itself.  You can hand deliver it yourself if you want, but if you want to mail it, you'll have to provide an envelope and a stamp. Note that, since you won't have a bank record of this payment, you'll want to make sure you keep other records, such as the stub of the money order.  You should probably also ask the contractor to give you a receipt."], 'contexts': ['Sure you can.  You can fill in whatever you want in the From section of a money order, so your business name and address would be fine. The price only includes the money order itself.  You can hand deliver it yourself if you want, but if you want to mail it, you\'ll have to provide an envelope and a stamp. Note that, since you won\'t have a bank record of this payment, you\'ll want to make sure you keep other records, such as the stub of the money order.  You should probably also ask the contractor to give you a receipt."Lets say you owed me $123.00 an wanted to mail me a check. I would then take the check from my mailbox an either take it to my bank, or scan it and deposit it via their electronic interface. Prior to you mailing it you would have no idea which bank I would use, or what my account number is. In fact I could have multiple bank accounts, so I could decide which one to deposit it into depending on what I wanted to do with the money, or which bank paid the most interest, or by coin flip. Now once the check is deposited my bank would then ""stamp"" the check with their name, their routing number, the date, an my account number. Eventually an image of the canceled check would then end up back at your bank. Which they would either send to you, or make available to you via their banking website. You don\'t mail it to my bank. You mail it to my home, or my business, or wherever I tell you to mail it. Some business give you the address of another location, where either a 3rd party processes all their checks, or a central location  where all the money for multiple branches are processed. If you do owe a company they will generally ask that in the memo section in the lower left corner that you include your customer number. This is to make sure that if they have multiple Juans the money is accounted correctly. In all my dealings will paying bills and mailing checks I have never been asked to send a check directly to the bank. If they want you to do exactly as you describe, they should provide you with a form or other instructions.""I have checked with Bank of America, and they say the ONLY way to cash (or deposit, or otherwise get access to the funds represented by a check made out to my business) is to open a business account. They tell me this is a Federal regulation, and every bank will say the same thing.  To do this, I need a state-issued ""dba"" certificate (from the county clerk\'s office) as well as an Employer ID Number (EIN) issued by the IRS. AND their CHEAPEST business banking account costs $15 / month. I think I can go to the bank that the check is drawn upon, and they will cash it, assuming I have documentation showing that I am the sole proprietor. But I\'m not sure.... What a racket!!"'], 'retrieved_contexts': ['Sure you can.  You can fill in whatever you want in the From section of a money order, so your business name and address would be fine. The price only includes the money order itself.  You can hand deliver it yourself if you want, but if you want to mail it, you\'ll have to provide an envelope and a stamp. Note that, since you won\'t have a bank record of this payment, you\'ll want to make sure you keep other records, such as the stub of the money order.  You should probably also ask the contractor to give you a receipt."Lets say you owed me $123.00 an wanted to mail me a check. I would then take the check from my mailbox an either take it to my bank, or scan it and deposit it via their electronic interface. Prior to you mailing it you would have no idea which bank I would use, or what my account number is. In fact I could have multiple bank accounts, so I could decide which one to deposit it into depending on what I wanted to do with the money, or which bank paid the most interest, or by coin flip. Now once the check is deposited my bank would then ""stamp"" the check with their name, their routing number, the date, an my account number. Eventually an image of the canceled check would then end up back at your bank. Which they would either send to you, or make available to you via their banking website. You don\'t mail it to my bank. You mail it to my home, or my business, or wherever I tell you to mail it. Some business give you the address of another location, where either a 3rd party processes all their checks, or a central location  where all the money for multiple branches are processed. If you do owe a company they will generally ask that in the memo section in the lower left corner that you include your customer number. This is to make sure that if they have multiple Juans the money is accounted correctly. In all my dealings will paying bills and mailing checks I have never been asked to send a check directly to the bank. If they want you to do exactly as you describe, they should provide you with a form or other instructions.""I have checked with Bank of America, and they say the ONLY way to cash (or deposit, or otherwise get access to the funds represented by a check made out to my business) is to open a business account. They tell me this is a Federal regulation, and every bank will say the same thing.  To do this, I need a state-issued ""dba"" certificate (from the county clerk\'s office) as well as an Employer ID Number (EIN) issued by the IRS. AND their CHEAPEST business banking account costs $15 / month. I think I can go to the bank that the check is drawn upon, and they will cash it, assuming I have documentation showing that I am the sole proprietor. But I\'m not sure.... What a racket!!"'], 'response': '\nYes, you can send a money order from USPS as a business. You can fill in whatever you want in the From section of the money order, including your business name and address. The price only includes the money order itself, so you will need to provide an envelope and a stamp if you want to mail it. It is important to keep records of the payment, such as the stub of the money order, and to ask the contractor for a receipt.'}, task_output={'user_input': 'Can I send a money order from USPS as a business?', 'response': '\nYes, you can send a money order from USPS as a business. You can fill in whatever you want in the From section of the money order, including your business name and address. The price only includes the money order itself, so you will need to provide an envelope and a stamp if you want to mail it. It is important to keep records of the payment, such as the stub of the money order, and to ask the contractor for a receipt.', 'retrieved_contexts': ['Sure you can.  You can fill in whatever you want in the From section of a money order, so your business name and address would be fine. The price only includes the money order itself.  You can hand deliver it yourself if you want, but if you want to mail it, you\'ll have to provide an envelope and a stamp. Note that, since you won\'t have a bank record of this payment, you\'ll want to make sure you keep other records, such as the stub of the money order.  You should probably also ask the contractor to give you a receipt."Lets say you owed me $123.00 an wanted to mail me a check. I would then take the check from my mailbox an either take it to my bank, or scan it and deposit it via their electronic interface. Prior to you mailing it you would have no idea which bank I would use, or what my account number is. In fact I could have multiple bank accounts, so I could decide which one to deposit it into depending on what I wanted to do with the money, or which bank paid the most interest, or by coin flip. Now once the check is deposited my bank would then ""stamp"" the check with their name, their routing number, the date, an my account number. Eventually an image of the canceled check would then end up back at your bank. Which they would either send to you, or make available to you via their banking website. You don\'t mail it to my bank. You mail it to my home, or my business, or wherever I tell you to mail it. Some business give you the address of another location, where either a 3rd party processes all their checks, or a central location  where all the money for multiple branches are processed. If you do owe a company they will generally ask that in the memo section in the lower left corner that you include your customer number. This is to make sure that if they have multiple Juans the money is accounted correctly. In all my dealings will paying bills and mailing checks I have never been asked to send a check directly to the bank. If they want you to do exactly as you describe, they should provide you with a form or other instructions.""I have checked with Bank of America, and they say the ONLY way to cash (or deposit, or otherwise get access to the funds represented by a check made out to my business) is to open a business account. They tell me this is a Federal regulation, and every bank will say the same thing.  To do this, I need a state-issued ""dba"" certificate (from the county clerk\'s office) as well as an Employer ID Number (EIN) issued by the IRS. AND their CHEAPEST business banking account costs $15 / month. I think I can go to the bank that the check is drawn upon, and they will cash it, assuming I have documentation showing that I am the sole proprietor. But I\'m not sure.... What a racket!!"']}), score_results=[ScoreResult(name='answer_relevancy_metric', value=0.9999999999999996, reason=None, metadata=None, scoring_failed=False)]), TestResult(test_case=TestCase(trace_id='06747336-2699-7913-8000-0cf7ed3e76f8', dataset_item_id='06745f1a-1e67-7d45-8000-730b2f4bfd7c', scoring_inputs={'reference': "You're confusing a lot of things here. Company B LLC will have it's sales run under Company A LLC, and cease operating as a separate entity These two are contradicting each other. If B LLC ceases to exist - it is not going to have it's sales run under A LLC, since there will be no sales to run for a non-existent company. What happens is that you merge B LLC into A LLC, and then convert A LLC into S Corp. So you're cancelling the EIN for B LLC, you're cancelling the EIN for A LLC - because both entities cease to exist. You then create a EIN for A Corp, which is the converted A LLC, and you create a DBA where A Corp DBA B Shop. You then go to the bank and open the account for A Corp DBA B Shop with the EIN you just created for A Corp. Get a better accountant. Before you convert to S-Corp.", 'question': '1 EIN doing business under multiple business names', 'answer': '\nYes, it is possible to have one EIN doing business under multiple business names. This can be done by filing a "Doing Business As" (DBA) document with the local government and having the bank call the county seat to verify the DBA. The DBA form will need to be processed and recorded, and there may be a fee associated with this.', 'user_input': '1 EIN doing business under multiple business names', 'ground_truths': ["You're confusing a lot of things here. Company B LLC will have it's sales run under Company A LLC, and cease operating as a separate entity These two are contradicting each other. If B LLC ceases to exist - it is not going to have it's sales run under A LLC, since there will be no sales to run for a non-existent company. What happens is that you merge B LLC into A LLC, and then convert A LLC into S Corp. So you're cancelling the EIN for B LLC, you're cancelling the EIN for A LLC - because both entities cease to exist. You then create a EIN for A Corp, which is the converted A LLC, and you create a DBA where A Corp DBA B Shop. You then go to the bank and open the account for A Corp DBA B Shop with the EIN you just created for A Corp. Get a better accountant. Before you convert to S-Corp."], 'contexts': ['You\'re confusing a lot of things here. Company B LLC will have it\'s sales run under Company A LLC, and cease operating as a separate entity These two are contradicting each other. If B LLC ceases to exist - it is not going to have it\'s sales run under A LLC, since there will be no sales to run for a non-existent company. What happens is that you merge B LLC into A LLC, and then convert A LLC into S Corp. So you\'re cancelling the EIN for B LLC, you\'re cancelling the EIN for A LLC - because both entities cease to exist. You then create a EIN for A Corp, which is the converted A LLC, and you create a DBA where A Corp DBA B Shop. You then go to the bank and open the account for A Corp DBA B Shop with the EIN you just created for A Corp. Get a better accountant. Before you convert to S-Corp.You don\'t need to notify the IRS of new members, the IRS doesn\'t care (at this stage). What you do need, if you have a EIN for a single-member LLC, is to request a new EIN since your LLC is now a partnership (a different entity, from IRS perspective). From now on, you\'ll need to file form 1065 with the IRS in case of business related income, on which you will declare the membership distribution interests on Schedules K-1 for each member."Depending on where you are, you may be able to get away with filing a ""Doing Business As"" document with your local government, and then having the bank call the county seat to verify this. There is generally a fee for processing/recording/filing the DBA form, of course. But it\'s useful for more purposes than just this one. (I still need to file a DBA for my hobby work-for-pay, for exactly this reason.)"'], 'retrieved_contexts': ['You\'re confusing a lot of things here. Company B LLC will have it\'s sales run under Company A LLC, and cease operating as a separate entity These two are contradicting each other. If B LLC ceases to exist - it is not going to have it\'s sales run under A LLC, since there will be no sales to run for a non-existent company. What happens is that you merge B LLC into A LLC, and then convert A LLC into S Corp. So you\'re cancelling the EIN for B LLC, you\'re cancelling the EIN for A LLC - because both entities cease to exist. You then create a EIN for A Corp, which is the converted A LLC, and you create a DBA where A Corp DBA B Shop. You then go to the bank and open the account for A Corp DBA B Shop with the EIN you just created for A Corp. Get a better accountant. Before you convert to S-Corp.You don\'t need to notify the IRS of new members, the IRS doesn\'t care (at this stage). What you do need, if you have a EIN for a single-member LLC, is to request a new EIN since your LLC is now a partnership (a different entity, from IRS perspective). From now on, you\'ll need to file form 1065 with the IRS in case of business related income, on which you will declare the membership distribution interests on Schedules K-1 for each member."Depending on where you are, you may be able to get away with filing a ""Doing Business As"" document with your local government, and then having the bank call the county seat to verify this. There is generally a fee for processing/recording/filing the DBA form, of course. But it\'s useful for more purposes than just this one. (I still need to file a DBA for my hobby work-for-pay, for exactly this reason.)"'], 'response': '\nYes, it is possible to have one EIN doing business under multiple business names. This can be done by filing a "Doing Business As" (DBA) document with the local government and having the bank call the county seat to verify the DBA. The DBA form will need to be processed and recorded, and there may be a fee associated with this.'}, task_output={'user_input': '1 EIN doing business under multiple business names', 'response': '\nYes, it is possible to have one EIN doing business under multiple business names. This can be done by filing a "Doing Business As" (DBA) document with the local government and having the bank call the county seat to verify the DBA. The DBA form will need to be processed and recorded, and there may be a fee associated with this.', 'retrieved_contexts': ['You\'re confusing a lot of things here. Company B LLC will have it\'s sales run under Company A LLC, and cease operating as a separate entity These two are contradicting each other. If B LLC ceases to exist - it is not going to have it\'s sales run under A LLC, since there will be no sales to run for a non-existent company. What happens is that you merge B LLC into A LLC, and then convert A LLC into S Corp. So you\'re cancelling the EIN for B LLC, you\'re cancelling the EIN for A LLC - because both entities cease to exist. You then create a EIN for A Corp, which is the converted A LLC, and you create a DBA where A Corp DBA B Shop. You then go to the bank and open the account for A Corp DBA B Shop with the EIN you just created for A Corp. Get a better accountant. Before you convert to S-Corp.You don\'t need to notify the IRS of new members, the IRS doesn\'t care (at this stage). What you do need, if you have a EIN for a single-member LLC, is to request a new EIN since your LLC is now a partnership (a different entity, from IRS perspective). From now on, you\'ll need to file form 1065 with the IRS in case of business related income, on which you will declare the membership distribution interests on Schedules K-1 for each member."Depending on where you are, you may be able to get away with filing a ""Doing Business As"" document with your local government, and then having the bank call the county seat to verify this. There is generally a fee for processing/recording/filing the DBA form, of course. But it\'s useful for more purposes than just this one. (I still need to file a DBA for my hobby work-for-pay, for exactly this reason.)"']}), score_results=[ScoreResult(name='answer_relevancy_metric', value=0.9999999999999996, reason=None, metadata=None, scoring_failed=False)])])



#### Evaluating datasets using the Ragas `evaluate` function

If you looking at evaluating a dataset, you can use the Ragas `evaluate` function. When using this function, the Ragas library will compute the metrics on all the rows of the dataset and return a summary of the results.

You can use the `OpikTracer` callback to log the results of the evaluation to the Opik platform:


```python
from datasets import load_dataset
from ragas.metrics import context_precision, answer_relevancy, faithfulness
from ragas import evaluate

fiqa_eval = load_dataset("explodinggradients/fiqa", "ragas_eval")

# Reformat the dataset to match the schema expected by the Ragas evaluate function
dataset = fiqa_eval["baseline"].select(range(3))

dataset = dataset.map(
    lambda x: {
        "user_input": x["question"],
        "reference": x["ground_truths"][0],
        "retrieved_contexts": x["contexts"],
    }
)

opik_tracer_eval = OpikTracer(tags=["ragas_eval"], metadata={"evaluation_run": True})

result = evaluate(
    dataset,
    metrics=[context_precision, faithfulness, answer_relevancy],
    callbacks=[opik_tracer_eval],
)

print(result)
```


    Evaluating:   0%|          | 0/9 [00:00<?, ?it/s]


    OPIK: Started logging traces to the "ragas-integration" project at https://www.comet.com/opik/jacques-comet/redirect/projects?name=ragas-integration.


    {'context_precision': 1.0000, 'faithfulness': 0.6917, 'answer_relevancy': 0.9648}



```python

```
