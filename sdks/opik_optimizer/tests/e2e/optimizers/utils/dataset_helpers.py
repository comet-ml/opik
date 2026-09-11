import opik


def remove_old_datasets(names: list[str]) -> None:
    client = opik.api_objects.opik_client.get_client_cached()
    for name in names:
        print(f"Deleting dataset: {name}")
        try:
            client.delete_dataset(name)
        except Exception as e:
            print(f"Failed to delete dataset: {name}, ignoring. Reason: {e}")
