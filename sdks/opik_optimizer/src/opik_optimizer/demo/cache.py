from urllib.parse import urlparse, parse_qs
import sqlite3
import shutil
import os
import litellm
from litellm.caching import Cache
import requests

NAMED_CACHES = {
    "test": "https://drive.google.com/file/d/1RifNtpN-pl0DW49daRaAMJwW7MCsOh6y/view?usp=sharing",
    "test2": "https://drive.google.com/uc?id=1RifNtpN-pl0DW49daRaAMJwW7MCsOh6y&export=download",
    "opik-workshop": "https://drive.google.com/file/d/1l0aK6KhDPs2bFsQTkfzvOvfacJlhdmHr/view?usp=sharing",
}
CACHE_DIR = os.path.expanduser("~/.litellm_cache")


def get_litellm_cache(name: str):
    """
    Get a LiteLLM cache from a remote location, and add it to the
    local cache
    """
    # Try to close an existing one, if there is one:
    try:
        litellm.cache.cache.disk_cache.close()
    except Exception:
        pass

    if not os.path.exists(CACHE_DIR):
        os.makedirs(CACHE_DIR)

    if name.lower() in NAMED_CACHES:
        return get_litellm_cache(NAMED_CACHES[name.lower()])
    elif name.startswith("https://drive.google.com/file/d/"):
        file_id = name.split("/d/")[1].split("/view")[0]
        download_url = f"https://drive.google.com/uc?id={file_id}&export=download"
        file_path = _get_google_drive_file(download_url)
    elif name.startswith("https://drive.google.com/uc"):
        file_path = _get_google_drive_file(name)
    else:
        raise Exception("Unknown cache type: %r" % name)

    dest_path = os.path.join(CACHE_DIR, "cache.db")

    if os.path.exists(dest_path):
        # Copy contents from source to dest:
        _copy_cache(file_path, dest_path)
    else:
        # Just copy the file:
        shutil.copy(file_path, dest_path)

    # Update the cache to use the new database:
    litellm.cache = Cache(type="disk", disk_cache_dir=CACHE_DIR)


def _copy_cache(source_path, dest_path):
    """
    Copy cached items from a source to a destination cache.
    """
    source_conn = sqlite3.connect(source_path)
    source_conn.row_factory = sqlite3.Row
    source_cursor = source_conn.cursor()

    dest_conn = sqlite3.connect(dest_path)
    dest_cursor = dest_conn.cursor()

    source_cursor.execute(f"PRAGMA table_info(Cache)")
    columns_info = source_cursor.fetchall()
    column_names = [info[1] for info in columns_info[1:]]  # Skip rowid
    placeholders = ", ".join(["?"] * len(column_names))
    columns_str = ", ".join(column_names)

    inserted_count = 0
    source_cursor.execute("SELECT * FROM Cache")
    records = source_cursor.fetchall()
    for record in records:
        record = dict(record)
        del record["rowid"]
        key_value = record["key"]

        dest_cursor.execute("SELECT 1 FROM Cache WHERE key = ?", (key_value,))
        existing_record = dest_cursor.fetchone()

        if not existing_record:
            dest_cursor.execute(
                f"INSERT INTO Cache ({columns_str}) VALUES ({placeholders})",
                list(record.values()),
            )
            inserted_count += 1

    print(f"Inserted {inserted_count} record(s) in litellm cache")
    dest_conn.commit()


def _get_google_drive_file(file_url):
    """
    Given a common google drive URL with id=ID
    get it, or use cache.
    """
    parsed_url = urlparse(file_url)
    query_params = parse_qs(parsed_url.query)
    id_value = query_params.get("id")[0]

    cache_file_path = os.path.join(CACHE_DIR, id_value)

    if not os.path.exists(cache_file_path):
        response = requests.get(file_url)
        response.raise_for_status()

        with open(cache_file_path, "wb") as tmp_file:
            for chunk in response.iter_content(chunk_size=8192):
                tmp_file.write(chunk)

    return cache_file_path
