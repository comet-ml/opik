import opik

def gsm8k(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the GSM8K dataset.
    """
    dataset_name = "gsm8k" if not test_mode else "gsm8k_test"
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
        hf_dataset = ds.load_dataset("gsm8k", "main", streaming=True, download_config=download_config)
        
        data = []
        for i, item in enumerate(hf_dataset["train"]):
            if i >= nb_items:
                break
            data.append({
                "question": item["question"],
                "answer": item["answer"],
            })
        ds.enable_progress_bar()

        dataset.insert(data)

        return dataset
