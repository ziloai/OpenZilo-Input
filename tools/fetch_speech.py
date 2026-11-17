import hashlib
import os
import pathlib
import sys
import urllib.request

VERSION = "2.0.1"
SIZE = 49441341
SHA256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar"

output = pathlib.Path(sys.argv[1])
output.parent.mkdir(parents=True, exist_ok=True)
partial = output.with_suffix(".download")
try:
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(URL, timeout=60) as response, partial.open("wb") as destination:
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > SIZE:
                raise ValueError("Speech runtime exceeds expected size")
            digest.update(chunk)
            destination.write(chunk)
    if size != SIZE or digest.hexdigest() != SHA256:
        raise ValueError("Speech runtime checksum mismatch")
    os.replace(partial, output)
finally:
    partial.unlink(missing_ok=True)
