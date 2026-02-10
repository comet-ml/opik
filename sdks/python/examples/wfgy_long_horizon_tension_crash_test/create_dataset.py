# WFGY / Opik long-horizon dataset smoke test (single cell)

import os
import getpass
from pathlib import Path

# 1) Clone your fork of opik (only if not already cloned)
REPO_URL = "https://github.com/onestardao/opik.git"
REPO_DIR = Path("opik")

if not REPO_DIR.exists():
    print(f"Cloning {REPO_URL} ...")
    # `!` is executed by IPython, safe to call here
    get_ipython().system(f"git clone {REPO_URL}")
else:
    print("Repository 'opik' already exists, skipping clone.")

# 2) Change directory into the repo
os.chdir(REPO_DIR)
print("Current working directory:", os.getcwd())

# 3) Install Opik SDK (quiet mode)
print("Installing 'opik' Python package...")
get_ipython().system("pip install -q opik")

# 4) Configure Opik credentials (Opik Cloud)
#    You must create an Opik Cloud account and an API key in advance.
if "OPIK_API_KEY" not in os.environ:
    os.environ["OPIK_API_KEY"] = getpass.getpass("Enter your Opik API key: ")

if "OPIK_WORKSPACE" not in os.environ:
    os.environ["OPIK_WORKSPACE"] = input("Enter your Opik workspace name (usually your username): ")

print("OPIK_API_KEY and OPIK_WORKSPACE are set.")

# 5) Go to the WFGY example folder
example_dir = Path("sdks/python/examples/wfgy_long_horizon_tension_crash_test")
if not example_dir.exists():
    raise FileNotFoundError(f"Example folder not found: {example_dir}")

os.chdir(example_dir)
print("Now in example folder:", os.getcwd())
print("Folder contents:")
get_ipython().system("ls")

# 6) Run create_dataset.py to push items into Opik
print("\nRunning create_dataset.py ...")
exit_code = get_ipython().system("python create_dataset.py")

if exit_code == 0:
    print("\ncreate_dataset.py finished successfully.")
    print("You should now see a dataset named")
    print("  'wfgy_long_horizon_tension_crash_test'")
    print("in the Datasets section of your Opik workspace.")
else:
    print("\ncreate_dataset.py exited with a non-zero status. Check the error above.")
