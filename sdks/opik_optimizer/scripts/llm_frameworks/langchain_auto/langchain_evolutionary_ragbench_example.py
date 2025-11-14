"""
RAG Pipeline Optimization using Opik Evolutionary Optimizer

This module provides a simple API for optimizing LangChain RAG pipelines using
the Opik EvolutionaryOptimizer, similar to the optimize_adk_agent pattern.

Quick Start:
    >>> import opik
    >>> from optimize_rag_opik import optimize_langchain_rag, setup_rag_chain, levenshtein_ratio
    >>> from opik_optimizer import EvolutionaryOptimizer
    >>> 
    >>> # Load your dataset from Opik
    >>> opik_client = opik.Opik()
    >>> dataset = opik_client.get_dataset(name="my-dataset-name")
    >>> 
    >>> # Create optimizable agent (pass your setup function)
    >>> prompt, agent_class = optimize_langchain_rag(dataset, setup_rag_chain)
    >>> 
    >>> # Run optimization
    >>> optimizer = EvolutionaryOptimizer(...)
    >>> result = optimizer.optimize_prompt(
    ...     prompt=prompt,
    ...     agent_class=agent_class,
    ...     dataset=dataset,
    ...     metric=levenshtein_ratio
    ... )

Key Components:
    - optimize_langchain_rag(): Create optimizable RAG agent (main API - similar to optimize_adk_agent)
    - setup_rag_chain(): Default RAG chain setup function (can be customized)
    - optimize_system_prompt(): Inject trial prompts during optimization (use in your setup functions)
    - levenshtein_ratio(): Evaluation metric
    - DEFAULT_SYSTEM_PROMPT: Default RAG prompt template
"""

import os
from typing import Any
import opik
from langchain_openai import OpenAIEmbeddings, ChatOpenAI
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import RunnablePassthrough
from langchain_core.output_parsers import StrOutputParser
from langchain_text_splitters import RecursiveCharacterTextSplitter
from opik.integrations.langchain import OpikTracer
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer import ChatPrompt, EvolutionaryOptimizer, OptimizableAgent


# Configuration
DATASET_NAME = "ragbench-covidqa-10"  # Opik dataset name
CHUNK_SIZE = 1000
CHUNK_OVERLAP = 200

# Default RAG system prompt template
# Must include {context} and {question} placeholders
DEFAULT_SYSTEM_PROMPT = """Answer the question based only on the following context:

{context}

Question: {question}

Answer:"""

# Optimization state management (similar to ADK example)
OPTIMIZABLE_STATUS = None
OPTIMIZABLE_PARAMETERS = []
OPTIMIZABLE_PARAMETERS_VALUES = {}


def get_trial_value(id: str, default: str, type: str) -> str | None:
    """Get trial value during optimization or collect parameter during discovery"""
    if OPTIMIZABLE_STATUS == "collect":
        OPTIMIZABLE_PARAMETERS.append({"id": id, "type": type, "default": default})
    elif OPTIMIZABLE_STATUS == "optimize":
        return OPTIMIZABLE_PARAMETERS_VALUES.get(id)
    return None


def optimize_system_prompt(prompt: str, prompt_id: str = "system") -> str:
    """
    Inject optimized system prompt during optimization trials.
    
    During collection phase: registers the prompt as optimizable
    During optimization phase: returns the trial prompt from optimizer
    Otherwise: returns the original prompt
    
    Args:
        prompt: Default system prompt
        prompt_id: Identifier for this prompt (default: "system")
    
    Returns:
        Original prompt or optimized trial prompt
    """
    new_prompt = get_trial_value(prompt_id, default=prompt, type="system")
    if not new_prompt:
        return prompt
    return new_prompt


def create_documents_from_texts(documents_texts):
    """Create LangChain documents from text list"""
    documents = []
    for idx, doc_text in enumerate(documents_texts):
        documents.append(
            Document(
                page_content=doc_text,
                metadata={"source": f"doc_{idx}"}
            )
        )
    return documents


def format_docs(docs):
    """Format documents for the prompt"""
    return "\n\n".join(doc.page_content for doc in docs)


def setup_rag_chain(documents=None, system_prompt=DEFAULT_SYSTEM_PROMPT, chunk_size=CHUNK_SIZE, chunk_overlap=CHUNK_OVERLAP):
    """Setup the RAG pipeline with customizable system prompt
    
    This function uses optimize_system_prompt() to allow prompt optimization.
    During optimization, trial prompts will be automatically injected.
    
    Args:
        documents: List of Document objects to ingest (optional, can be None for parameter collection)
        system_prompt: The system prompt template to use (default: DEFAULT_SYSTEM_PROMPT)
        chunk_size: Size of text chunks for splitting (default: CHUNK_SIZE)
        chunk_overlap: Overlap between chunks (default: CHUNK_OVERLAP)
    
    Returns:
        RAG chain (uses dummy documents if none provided, for parameter collection)
    """
    # Check for OpenAI API key
    if not os.getenv("OPENAI_API_KEY"):
        raise ValueError("Please set OPENAI_API_KEY environment variable")
    
    # Apply optimization injection here (where prompt is actually used)
    # This allows the optimizer to inject trial prompts
    system_prompt = optimize_system_prompt(system_prompt)
    
    # If no documents provided, we're in collection mode - create minimal setup
    if documents is None:
        # Create empty documents for parameter collection
        documents = [Document(page_content="dummy", metadata={"source": "dummy"})]
    
    # Initialize text splitter with provided parameters
    text_splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        length_function=len,
        is_separator_regex=False,
    )
    
    # Split documents into chunks
    split_documents = text_splitter.split_documents(documents)
    
    # Initialize embeddings
    embeddings = OpenAIEmbeddings()
    
    # Create in-memory vector store from split documents
    vectorstore = FAISS.from_documents(split_documents, embeddings)
    
    # Initialize LLM
    llm = ChatOpenAI(model="gpt-3.5-turbo", temperature=0)
    
    # Create retriever
    retriever = vectorstore.as_retriever(search_kwargs={"k": 2})
    
    # Create prompt template with optimized system prompt
    prompt = ChatPromptTemplate.from_template(system_prompt)
    
    # Create simple RAG chain
    rag_chain = (
        {"context": retriever | format_docs, "question": RunnablePassthrough()}
        | prompt
        | llm
        | StrOutputParser()
    )
    
    return rag_chain


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Metric function to evaluate RAG outputs"""
    metric = LevenshteinRatio()
    # Opik dataset items use 'expected_answer' field
    return metric.score(reference=dataset_item["expected_answer"], output=llm_output)


def get_optimizable_parameters(setup_chain_fn: Any) -> list[dict[str, str]]:
    """
    Collect optimizable parameters by running setup in 'collect' mode.
    Similar to get_optimizable_parameters in ADK example.
    
    Args:
        setup_chain_fn: Function that creates the RAG chain
    
    Returns:
        List of optimizable parameter definitions
    """
    global OPTIMIZABLE_STATUS, OPTIMIZABLE_PARAMETERS
    
    # Start the collection of optimizable parameters
    OPTIMIZABLE_STATUS = "collect"
    
    # Call the user's setup function without documents (None)
    # This will trigger optimize_system_prompt which will register the prompt
    _ = setup_chain_fn()
    
    # Get collected parameters
    parameters = OPTIMIZABLE_PARAMETERS
    OPTIMIZABLE_PARAMETERS = []
    OPTIMIZABLE_STATUS = None
    
    return parameters


def optimize_langchain_rag(
    opik_dataset,
    setup_chain_fn: Any,
    project_name: str = "rag-optimization"
) -> tuple[ChatPrompt, type[OptimizableAgent]]:
    """
    Create an optimizable LangChain RAG agent for prompt optimization.
    
    This function encapsulates the complexity of creating an OptimizableAgent
    for LangChain RAG pipelines, similar to optimize_adk_agent for ADK agents.
    
    Args:
        opik_dataset: The Opik Dataset object containing questions, documents, and expected answers
        setup_chain_fn: Function that creates the RAG chain. Should accept documents and return a chain.
                       The function should use optimize_system_prompt() for any prompts to be optimized.
        project_name: Project name for Opik tracing (default: "rag-optimization")
    
    Returns:
        tuple: (ChatPrompt, OptimizableAgent_class) ready for EvolutionaryOptimizer
    
    Example:
        >>> import opik
        >>> from optimize_rag_opik import optimize_langchain_rag, setup_rag_chain
        >>> 
        >>> opik_client = opik.Opik()
        >>> dataset = opik_client.get_dataset(name="my-dataset")
        >>> 
        >>> # Create optimizable agent with your setup function
        >>> prompt, agent = optimize_langchain_rag(dataset, setup_rag_chain)
        >>> 
        >>> optimizer = EvolutionaryOptimizer(...)
        >>> result = optimizer.optimize_prompt(prompt=prompt, agent_class=agent, dataset=dataset, ...)
    """
    
    # Step 1: Collect all optimizable parameters (similar to ADK pattern)
    optimizable_parameters = get_optimizable_parameters(setup_chain_fn)
    
    # Extract the system prompt from collected parameters
    system_prompts = [x["default"] for x in optimizable_parameters if x["type"] == "system"]
    assert len(system_prompts) == 1, "Only one system prompt is supported"
    collected_system_prompt = system_prompts[0]
    
    # Create ChatPrompt with the collected system prompt
    chat_prompt = ChatPrompt(
        system=collected_system_prompt,
        user="{question}",
    )
    
    # Build dataset lookup dictionary once for O(1) access
    dataset_lookup = {}
    for item in opik_dataset.get_items():
        dataset_lookup[item["question"]] = item
    
    # Capture setup_chain_fn for use in invoke
    _setup_chain_fn = setup_chain_fn
    
    # Create OptimizableAgent class with all configuration in closure
    class LangChainRAGAgent(OptimizableAgent):
        """LangChain RAG agent configured for this specific dataset and settings"""
        
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            super().__init__(*args, **kwargs)
            self.opik_tracer = None
        
        def init_agent(self, prompt: ChatPrompt) -> None:
            """Initialize the agent with the given prompt"""
            self.prompt = prompt
            # Initialize Opik tracer with project name from closure
            self.opik_tracer = OpikTracer(project_name=project_name)
        
        def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
            """
            Invoke the RAG chain with the given messages
            
            Args:
                messages: List of message dicts with 'role' and 'content'
                         First message should be system prompt (trial prompt from optimizer)
                         Second message contains the question
            """
            global OPTIMIZABLE_STATUS, OPTIMIZABLE_PARAMETERS_VALUES
            
            # Extract trial prompt and question from messages
            system_message = messages[0]
            user_message = messages[1]
            trial_system_prompt = system_message["content"]
            question = user_message["content"]
            
            # Set optimization state to inject trial prompt
            # When setup_rag_chain calls optimize_system_prompt, it will get the trial prompt
            OPTIMIZABLE_STATUS = "optimize"
            OPTIMIZABLE_PARAMETERS_VALUES = {
                "system": trial_system_prompt,
            }
            
            # Look up the dataset item by question (from closure)
            if question not in dataset_lookup:
                raise ValueError(f"Question not found in dataset: {question}")
            
            dataset_item = dataset_lookup[question]
            documents_texts = dataset_item["documents"]
            documents = create_documents_from_texts(documents_texts)
            
            # Setup RAG chain using the user's setup function (from closure)
            # The setup function will call optimize_system_prompt which will inject the trial prompt
            rag_chain = _setup_chain_fn(documents)
            
            # Reset optimization state after setup
            OPTIMIZABLE_STATUS = None
            OPTIMIZABLE_PARAMETERS_VALUES = {}
            
            # Invoke the chain with Opik tracing
            if self.opik_tracer:
                answer = rag_chain.invoke(
                    question,
                    config={"callbacks": [self.opik_tracer]}
                )
            else:
                answer = rag_chain.invoke(question)
            
            return answer
    
    return chat_prompt, LangChainRAGAgent


def main():
    """Main function to run RAG optimization"""
    
    print("="*80)
    print("RAG Pipeline Optimization with Opik")
    print("="*80 + "\n")
    
    # Check for required API keys
    if not os.getenv("OPENAI_API_KEY"):
        raise ValueError("Please set OPENAI_API_KEY environment variable")
    
    if not os.getenv("OPIK_API_KEY"):
        print("WARNING: OPIK_API_KEY not set. Tracing may be limited.")
    
    # Load dataset from Opik using the API directly
    print(f"Loading dataset '{DATASET_NAME}' from Opik...")
    opik_client = opik.Opik()
    opik_dataset = opik_client.get_dataset(name=DATASET_NAME)
    num_items = len(list(opik_dataset.get_items()))
    print(f"✓ Loaded dataset: {DATASET_NAME}")
    print(f"✓ Dataset contains {num_items} items\n")
    
    # Create optimizable LangChain RAG agent
    # This hides the complexity similar to optimize_adk_agent
    # Pass setup_rag_chain function which uses DEFAULT_SYSTEM_PROMPT and module-level configs
    prompt, agent_class = optimize_langchain_rag(
        opik_dataset=opik_dataset,
        setup_chain_fn=setup_rag_chain
    )
    
    print("Initial prompt:")
    print(f"System: {prompt.system}\n")
    
    # Create optimizer
    print("Initializing EvolutionaryOptimizer...")
    optimizer = EvolutionaryOptimizer(
        model="openai/gpt-4o-mini",
        enable_moo=False,
        enable_llm_crossover=True,
        infer_output_style=True,
        verbose=1,
        # population_size=10,
        # num_generations=3,
    )
    
    print("\nStarting optimization...\n")
    print("="*80)
    
    # Run optimization
    # Pass the Opik dataset object directly to optimizer
    # The agent class builds its lookup dictionary from the dataset at initialization
    optimization_result = optimizer.optimize_prompt(
        prompt=prompt,
        agent_class=agent_class,
        dataset=opik_dataset,
        metric=levenshtein_ratio,
        # n_samples=5,  # Use first 5 samples for optimization
    )
    
    print("\n" + "="*80)
    print("Optimization Complete!")
    print("="*80 + "\n")
    
    # Display results
    optimization_result.display()


if __name__ == "__main__":
    main()

