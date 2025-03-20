import subprocess
import os

accesslog = "-"
errorlog = "-"
loglevel = "info"
capture_output = True

def when_ready(server):
    server.log.info("✔ Gunicorn is ready. Running post-start hook...")

    script_dir = os.path.dirname(os.path.abspath(__file__))
    post_start_script = os.path.join(script_dir, "post_start_task.sh")
    try:
        subprocess.Popen([post_start_script])
    except Exception as e:
        server.log.error(f"Post-start task failed: {e}")