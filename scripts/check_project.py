"""Checks invariants that must hold before packaging the offline application."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
assert not manifest.findall('uses-permission'), 'The app must request no permissions'
app = manifest.find('application')
assert app.attrib[ANDROID + 'allowBackup'] == 'false'
assert app.attrib[ANDROID + 'fullBackupContent'] == 'false'
rules = ET.parse(ROOT / 'app/src/main/res/xml/data_extraction_rules.xml').getroot()
for mode in ('cloud-backup', 'device-transfer'):
    excluded = {e.attrib['domain'] for e in rules.find(mode).findall('exclude')}
    assert excluded == {'root', 'file', 'database', 'sharedpref', 'external'}
for source in (ROOT / 'app/src/main').rglob('*'):
    if source.suffix in ('.java', '.cpp'):
        content = source.read_text()
        for api in ('java.net.', 'okhttp', 'HttpURLConnection', 'WebView', 'TAG_GPS_', 'TAG_BODY_SERIAL_NUMBER'):
            assert api not in content, f'Unexpected data or networking API {api}: {source}'
model_store = (ROOT / 'app/src/main/java/app/photoprompt/offline/ModelStore.java').read_text()
assert len(re.findall(r'"[0-9a-f]{64}"', model_store)) == 2
for xml in (ROOT / 'app/src/main').rglob('*.xml'):
    ET.parse(xml)
print('PASS: no permissions, backup excluded, no network clients or sensitive EXIF fields, pinned model hashes, valid XML')
