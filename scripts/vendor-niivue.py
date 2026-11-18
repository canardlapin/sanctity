"""Reproduce the same-origin NiiVue browser module and its redistribution notices."""
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
from urllib.parse import unquote
from urllib.request import urlopen

VERSION = "0.69.0"
ROOT = "https://raw.githubusercontent.com/niivue/niivue/53d450fe2e43c40c0d4ad6a2d8dc08ee37575f8c/"
SRI = "sha512-YMjQvJgD1W2b7DuoxkwlC4VxbrgIePRJ5qyQzXKxi2v2MBVg285P/CIQyg46oDDGDIcaNlbd+MhtO/edMbXJkA=="
OUT = Path(__file__).resolve().parents[1] / "modules/http/src/main/resources/sanctity/ui"


def fetch(url):
    with urlopen(url, timeout=60) as response:
        return response.read()


def package(url, integrity):
    data = fetch(url)
    alg, expected = integrity.split("-", 1)
    if base64.b64encode(hashlib.new(alg, data).digest()).decode() != expected:
        raise ValueError(f"Integrity mismatch for {url}")
    return tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")


def main():
    with package(f"https://registry.npmjs.org/@niivue/niivue/-/niivue-{VERSION}.tgz", SRI) as archive:
        # The official /min export contains one URL-encoded ES module string.
        # Decode at vendoring time so the browser can import a normal named asset.
        wrapper = archive.extractfile("package/build/index.min.js").read().decode()
        payload = wrapper.removeprefix("export const esm = ").strip().removesuffix(";")
        script = unquote(json.loads(payload)).encode()
    lock = json.loads(fetch(ROOT + "package-lock.json"))["packages"]
    notices = [f"@niivue/niivue {VERSION}\n{ROOT}LICENSE\n" + fetch(ROOT + "LICENSE").decode().strip()]
    pending = ["packages/niivue"]
    seen = set(pending)
    while pending:
        parent = pending.pop()
        for name in sorted(lock[parent].get("dependencies", {})):
            key = next(k for k in (parent + "/node_modules/" + name, "node_modules/" + name) if k in lock)
            if key in seen:
                continue
            seen.add(key)
            pending.append(key)
            dep = lock[key]
            with package(dep["resolved"], dep["integrity"]) as archive:
                files = sorted(m.name for m in archive if m.isfile() and
                               re.fullmatch(r"(licen[sc]e|copying|notice)([._-].*)?", Path(m.name).name, re.I))
                if not files:
                    raise ValueError("Missing upstream license for " + name)
                for file in files:
                    body = archive.extractfile(file).read().decode().strip()
                    notices.append(f"{name} {dep['version']} — {file}\n{body}")
    (OUT / f"niivue-{VERSION}.js").write_bytes(script)
    (OUT / "NOTICE-niivue.txt").write_text("\n\n".join(notices) + "\n")
    print(f"NiiVue {VERSION}, SHA-256 {hashlib.sha256(script).hexdigest()}")


if __name__ == "__main__":
    main()
