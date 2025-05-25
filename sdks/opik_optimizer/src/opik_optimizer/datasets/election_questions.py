import opik


def election_questions(
    test_mode: bool = False
) -> opik.Dataset:
    dataset_name = "election_questions" if not test_mode else "election_questions_test"
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
        hf_dataset = ds.load_dataset("Anthropic/election_questions", download_config=download_config)
        
        data = [
            {
                "question": item["question"],
                "label": item["label"]
            }
            for item in hf_dataset["test"].select(range(nb_items))
        ]
        ds.enable_progress_bar()
        dataset.insert(data)

        return dataset
