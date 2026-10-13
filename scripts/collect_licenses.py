"""Bundle native dependency notices; do not change or omit license texts."""
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[1]
vendor = root / 'vendor/llama.cpp'
out = root / 'app/src/main/assets/licenses/llama.cpp'
if not (vendor / 'LICENSE').exists():
    raise SystemExit('Missing pinned llama.cpp dependency')
count = 0
for source in vendor.rglob('*'):
    if not source.is_file() or not source.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'AUTHORS', 'COPYRIGHT')):
        continue
    destination = out / source.relative_to(vendor)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    count += 1
print(f'Bundled {count} native dependency notices')
