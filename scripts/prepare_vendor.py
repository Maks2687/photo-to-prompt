"""Fetch the pinned dependency for an Android Studio build. GitHub Actions uses checkout instead."""
from pathlib import Path, PurePosixPath
import io
import urllib.request
import zipfile

REF = 'af50f2f248b475d9760f8e67503d01c0f194f5f4'
destination = Path(__file__).resolve().parents[1] / 'vendor/llama.cpp'
if destination.exists():
    raise SystemExit('vendor/llama.cpp already exists. Check its revision before replacing it.')
url = f'https://codeload.github.com/ggml-org/llama.cpp/zip/{REF}'
with urllib.request.urlopen(url, timeout=60) as response:
    archive = response.read()
with zipfile.ZipFile(io.BytesIO(archive)) as source:
    for entry in source.infolist():
        parts = PurePosixPath(entry.filename).parts[1:]
        if not parts:
            continue
        if '..' in parts or any(part.startswith('/') for part in parts):
            raise ValueError('Unsafe archive entry')
        target = destination.joinpath(*parts)
        if entry.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(entry))
print(f'Prepared llama.cpp at {REF}')
