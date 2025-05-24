import opik

def cnn_dailymail(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 100 samples of the CNN Daily Mail dataset.
    """
    dataset_name = "cnn_dailymail" if not test_mode else "cnn_dailymail_test"
    nb_items = 100 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it.")
    elif len(items) == 0:
        import datasets as ds
        
        download_config = ds.DownloadConfig(download_desc=False, disable_tqdm=True)
        ds.disable_progress_bar()
        hf_dataset = ds.load_dataset("cnn_dailymail", "3.0.0", streaming=True, download_config=download_config)
        
        data = []
        for i, item in enumerate(hf_dataset["validation"]):
            if i >= nb_items:
                break
            data.append({
                "article": item["article"],
                "highlights": item["highlights"],
            })
        ds.enable_progress_bar()
        
        dataset.insert(data)
        
        return dataset
    