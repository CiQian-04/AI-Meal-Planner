#!/usr/bin/env python3
"""Export the OpenAPI specification from a running backend into docs/openapi.json.

Used by the Docs Pages workflow so the published documentation always matches the code.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.request

BASE_URL = os.environ.get("API_BASE_URL", "http://127.0.0.1:8080")
OUTPUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "openapi.json")


def main() -> int:
    url = f"{BASE_URL.rstrip('/')}/v3/api-docs"
    print(f"Fetching {url}")
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            payload = response.read()
    except Exception as exc:
        print(f"Failed to fetch the specification: {exc}", file=sys.stderr)
        return 1

    try:
        document = json.loads(payload)
    except json.JSONDecodeError as exc:
        print(f"Response was not valid JSON: {exc}", file=sys.stderr)
        return 1

    with open(OUTPUT, "w", encoding="utf-8") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    paths = len(document.get("paths", {}))
    print(f"Wrote {OUTPUT} ({paths} paths)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
