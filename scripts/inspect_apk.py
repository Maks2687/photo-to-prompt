"""Validate actual merged APK, rather than relying only on source manifest."""
import os
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile

apk = Path(sys.argv[1]).resolve()
sdk = Path(os.environ['ANDROID_HOME'])
analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
permissions = subprocess.check_output([str(analyzer), 'manifest', 'permissions', str(apk)], text=True).strip()
assert not permissions, f'Unexpected APK permissions: {permissions}'
manifest = subprocess.check_output([str(analyzer), 'manifest', 'print', str(apk)], text=True)
assert 'android:allowBackup="false"' in manifest
with ZipFile(apk) as package:
    names = package.namelist()
    assert 'lib/arm64-v8a/libphoto_ai.so' in names
    assert 'lib/arm64-v8a/libc++_shared.so' in names
    assert not any(name.endswith('.gguf') for name in names), 'Model should be imported by the user'
subprocess.run([str(sdk / 'build-tools/35.0.0/apksigner'), 'verify', '--verbose', str(apk)], check=True)
subprocess.run([str(sdk / 'build-tools/35.0.0/zipalign'), '-c', '-P', '16', '-v', '4', str(apk)], check=True)
print('PASS: signed arm64 APK, no permissions, backup disabled, 16 KB ZIP alignment')
