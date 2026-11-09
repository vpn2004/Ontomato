# Launches one platform Python program and records whether any query it ran through this
# engine's /dsl/execute endpoint returned a partial (truncated) answer.
#
# Usage: python query_runtime.py <program.py> [program args...]
# ONTOMATO_QUERY_STATUS_FILE names the status file owned by this one run and
# ONTOMATO_ENGINE_PORT the engine's server.port. The first partial answer the program decodes
# is written to the status file; the program's own output and errors stay untouched.
import json
import os
import runpy
import sys
from urllib.parse import urlsplit

STATUS_FILE = os.environ.pop("ONTOMATO_QUERY_STATUS_FILE")
ENGINE_PORT = int(os.environ.pop("ONTOMATO_ENGINE_PORT"))
ENGINE_HOSTS = ("localhost", "127.0.0.1", "::1")
DSL_EXECUTE_PATH = "/dsl/execute"
STATUS_KEYS = ("truncated", "rowLimit", "truncationHint")


def _is_engine_query(request):
    url = urlsplit(request.url)
    return (request.method == "POST" and url.hostname in ENGINE_HOSTS
            and url.port == ENGINE_PORT and url.path == DSL_EXECUTE_PATH)


def _record_partial_queries():
    try:
        import requests
    except ImportError:
        # Without requests the program cannot call /dsl/execute through the documented helper.
        return
    original_json = requests.Response.json
    recorded = {}

    def json_with_query_status(self, **kwargs):
        payload = original_json(self, **kwargs)
        if _is_engine_query(self.request) and isinstance(payload, dict):
            # 每个源窗口独立保留，后计算不会把其匹配总量误认为已经全量读取。
            for datum in payload.get("data") or []:
                if datum.get("truncated") is True and not recorded.get("truncated"):
                    recorded.update({key: datum.get(key) for key in STATUS_KEYS})
                windows = list(datum.get("sourceWindows") or [])
                if datum.get("resultWindow") is not None:
                    windows.append(datum["resultWindow"])
                for window in windows:
                    saved = recorded.setdefault("sourceWindows", [])
                    if window not in saved:
                        if len(saved) >= 256:
                            raise RuntimeError("Query source window evidence budget exceeded")
                        saved.append(window)
            if recorded:
                with open(STATUS_FILE, "w", encoding="utf-8") as status:
                    json.dump(recorded, status, ensure_ascii=False)
        return payload

    requests.Response.json = json_with_query_status


_record_partial_queries()
program = os.path.abspath(sys.argv[1])
sys.argv = sys.argv[1:]
sys.path[0] = os.path.dirname(program)
runpy.run_path(program, run_name="__main__")
