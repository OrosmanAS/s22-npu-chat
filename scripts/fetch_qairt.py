#!/usr/bin/env python3
"""Fetch only the QAIRT files this app needs from Qualcomm's public SDK zip (~2.6 GB).

Uses HTTP range requests (remotezip) so only ~20 MB is downloaded; falls back to downloading the
whole zip if the server stops honouring ranges. Produces an SDK-shaped directory that LiteRT's
@qairt Bazel repo accepts via LITERT_QAIRT_SDK:

  <out>/include/QNN/**                         headers for building the dispatch lib
  <out>/lib/aarch64-android/libQnnHtp.so       runtime libs bundled in the APK
  <out>/lib/aarch64-android/libQnnSystem.so
  <out>/lib/aarch64-android/libQnnHtpV69Stub.so
  <out>/lib/hexagon-v69/unsigned/libQnnHtpV69Skel.so
"""
import argparse
import os
import shutil
import subprocess
import sys
import zipfile

URL = "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/{v}/v{v}.zip"

RUNTIME_LIBS = [
    "lib/aarch64-android/libQnnHtp.so",
    "lib/aarch64-android/libQnnSystem.so",
    "lib/aarch64-android/libQnnHtpV69Stub.so",
    "lib/hexagon-v69/unsigned/libQnnHtpV69Skel.so",
]


def wanted(rel: str) -> bool:
    return (rel.startswith("include/QNN/") and not rel.endswith("/")) or rel in RUNTIME_LIBS or rel == "LICENSE.pdf"


def extract(zf, prefix: str, out: str) -> int:
    n = 0
    for info in zf.infolist():
        if not info.filename.startswith(prefix):
            continue
        rel = info.filename[len(prefix):]
        if not wanted(rel):
            continue
        dst = os.path.join(out, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with zf.open(info) as src, open(dst, "wb") as f:
            shutil.copyfileobj(src, f)
        n += 1
    return n


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    url = URL.format(v=a.version)
    prefix = f"qairt/{a.version}/"
    os.makedirs(a.out, exist_ok=True)

    try:
        from remotezip import RemoteZip
        print(f"range-fetching from {url}", flush=True)
        with RemoteZip(url) as zf:
            n = extract(zf, prefix, a.out)
    except Exception as e:  # noqa: BLE001
        print(f"range fetch failed ({e!r}); downloading the full zip", flush=True)
        zpath = os.path.join(a.out, "..", "qairt.zip")
        subprocess.check_call(["curl", "-fL", "--retry", "5", "-o", zpath, url])
        with zipfile.ZipFile(zpath) as zf:
            n = extract(zf, prefix, a.out)
        os.remove(zpath)

    missing = [p for p in RUNTIME_LIBS if not os.path.isfile(os.path.join(a.out, p))]
    if missing or not os.path.isfile(os.path.join(a.out, "include/QNN/QnnInterface.h")):
        print(f"missing after extract: {missing}", file=sys.stderr)
        return 1
    print(f"extracted {n} files into {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
