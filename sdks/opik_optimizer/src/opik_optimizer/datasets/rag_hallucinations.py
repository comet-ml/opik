import opik

def rag_hallucinations(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the RAG Hallucinations dataset.
    """
    dataset_name = "rag_hallucination" if not test_mode else "rag_hallucination_test"
    nb_items = 300 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it.")
    elif len(items) == 0:
        import datasets as ds

        # Load data from file and insert into the dataset
        download_config = ds.DownloadConfig(download_desc=False, disable_tqdm=True)
        ds.disable_progress_bar()
        hf_dataset = ds.load_dataset("aporia-ai/rag_hallucinations", download_config=download_config)
        
        data = [
            {
                "context": item["context"],
                "question": item["question"],
                "answer": item["answer"],
                "is_hallucination": item["is_hallucination"],
            }
            for item in hf_dataset["train"].select(range(nb_items))
        ]
        ds.enable_progress_bar()
        
        dataset.insert(data)

        return dataset
