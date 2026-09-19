#!/usr/bin/env python3
"""Publish the updater feed LAST, after a signed, immutable APK is downloadable.

Example: python3 scripts/publish-update-feed.py --apk /release/troop-reader-....apk
  --notes /release/update-notes.txt --public-dir /srv/troop-reader
  --aapt /android-sdk/build-tools/35.0.0/aapt --apksigner /android-sdk/build-tools/35.0.0/apksigner
Requires JAVA_HOME for apksigner. No credentials are read or sent.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

BASE = "https://claw.raishack.es/troop-reader/"

def signed_certificate(apksigner, apk):
    result = subprocess.run([apksigner, "verify", "--print-certs", str(apk)], check=True, capture_output=True, text=True)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", result.stdout)
    if not certs:
        raise ValueError("APK signing certificate could not be verified")
    return sorted(certs)

def download_matches(name, expected):
    request = urllib.request.Request(BASE + name, headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=45) as response:
        if response.status != 200 or response.url != BASE + name:
            raise ValueError("APK must be available directly over HTTPS without a redirect")
        digest = hashlib.sha256()
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ValueError("Public artifact differs from the validated APK")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "notes", "public-dir", "aapt", "apksigner"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    apk, public = Path(args.apk).resolve(), Path(args.public_dir).resolve()
    if not re.fullmatch(r"troop-reader-[0-9A-Za-z.+_-]+\.apk", apk.name):
        raise ValueError("Invalid APK filename")
    badging = subprocess.run([args.aapt, "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    minimum = re.search(r"sdkVersion:'(\d+)'", badging)
    if not package or not minimum or package[1] != "es.gamingtroop.reader":
        raise ValueError("Invalid application metadata")
    notes = Path(args.notes).read_text().strip()
    if len(notes) > 8000 or not re.fullmatch(r"[0-9A-Za-z.+_-]{1,64}", package[3]):
        raise ValueError("Invalid notes or version name")
    certs = signed_certificate(args.apksigner, apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    size = apk.stat().st_size
    if not 0 < size <= 200 * 1024 * 1024:
        raise ValueError("APK exceeds update size limit")
    current = public / "latest.json"
    if current.exists():
        old = json.loads(current.read_text())
        if int(package[2]) <= old["versionCode"]:
            raise ValueError("Versions must increase; do not overwrite a published release")
        old_apk = public / old["apkUrl"].rsplit("/", 1)[1]
        if certs != signed_certificate(args.apksigner, old_apk):
            raise ValueError("Signing key changed: installed apps could not upgrade")
    published = public / apk.name
    if not published.exists() or hashlib.sha256(published.read_bytes()).hexdigest() != digest:
        raise ValueError("Publish the immutable APK first")
    download_matches(apk.name, digest)
    release = dict(schemaVersion=1, packageName=package[1], versionCode=int(package[2]),
                   versionName=package[3], minSdk=int(minimum[1]), apkUrl=BASE+apk.name,
                   sizeBytes=size, sha256=digest, notes=notes)
    payload = (json.dumps(release, ensure_ascii=False, indent=2) + "\n").encode()
    if len(payload) > 32 * 1024:
        raise ValueError("Feed exceeds client size limit")
    if current.exists():
        (public / ("latest-before-"+package[2]+".json")).write_bytes(current.read_bytes())
    staged = public / "latest.json.new"
    with staged.open("wb") as file:
        file.write(payload); file.flush(); os.fsync(file.fileno())
    os.replace(staged, current)
    download_matches("latest.json", hashlib.sha256(payload).hexdigest())
    print(json.dumps(release, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
