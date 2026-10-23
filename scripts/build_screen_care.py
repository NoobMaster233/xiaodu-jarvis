#!/usr/bin/env python3
"""Build the local API-27 system clock, using pinned local SDK inputs.

No Gradle, network dependency resolution, third-party runtime, or agent client.
Signing credentials stay in private/screen-care-signing (0700/0600).
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "system/screen-care"
SDK = ROOT / ".cache/android-mini"
BUILD = ROOT / "artifacts/screen-care/build"
PRIVATE = ROOT / "private/screen-care-signing"


def run(args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT, **kwargs)


def main():
    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True, exist_ok=True)
    for folder in ["generated", "classes", "dex"]:
        (BUILD / folder).mkdir(exist_ok=True)
    PRIVATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    PRIVATE.chmod(0o700)
    password = PRIVATE / "password"
    if not password.exists():
        password.write_text(secrets.token_hex(24))
        password.chmod(0o600)
    env = os.environ.copy()
    env["XIAODU_SCREENCARE_SIGNING_PASSWORD"] = password.read_text().strip()
    key = PRIVATE / "screen-care.p12"
    if not key.exists():
        run(["keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
             "-storepass:env", "XIAODU_SCREENCARE_SIGNING_PASSWORD", "-alias", "screen-care",
             "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
             "-dname", "CN=Xiaodu Local Screen Care"], env=env)
        key.chmod(0o600)
    unsigned = BUILD / "unsigned.apk"
    run(["aapt", "package", "-f", "-M", SOURCE / "AndroidManifest.xml", "-S", SOURCE / "res",
         "-I", SDK / "android-27.jar", "-F", unsigned, "-J", BUILD / "generated"])
    sources = sorted((SOURCE / "src").rglob("*.java")) + sorted((BUILD / "generated").rglob("*.java"))
    run(["javac", "-encoding", "UTF-8", "--release", "8",
         "-classpath", SDK / "android-27.jar", "-d", BUILD / "classes", *sources])
    run(["java", "-cp", SDK / "r8.jar", "com.android.tools.r8.D8", "--release",
         "--min-api", "27", "--lib", SDK / "android-27.jar", "--output", BUILD / "dex",
         *sorted((BUILD / "classes").rglob("*.class"))])
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(BUILD / "dex/classes.dex", "classes.dex")
    aligned = BUILD / "aligned.apk"
    run(["zipalign", "-f", "4", unsigned, aligned])
    version = ET.parse(SOURCE / "AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName")
    result = BUILD.parent / f"screen-care-{version}.apk"
    run(["apksigner", "sign", "--ks", key, "--ks-key-alias", "screen-care", "--ks-pass",
         "env:XIAODU_SCREENCARE_SIGNING_PASSWORD", "--out", result, aligned], env=env)
    run(["apksigner", "verify", "--verbose", result])
    proof = {"apk": str(result.relative_to(ROOT)),
             "sha256": hashlib.sha256(result.read_bytes()).hexdigest(),
             "size": result.stat().st_size,
             "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in sorted(SOURCE.rglob("*")) if p.is_file()}}
    proof["build_script_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (BUILD.parent / "build-proof.json").write_text(json.dumps(proof, indent=2))
    print(json.dumps({k: v for k, v in proof.items() if k != "sources"}, indent=2))


if __name__ == "__main__":
    main()
