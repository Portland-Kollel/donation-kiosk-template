"""Public artifact identity only. Never reads or exports a private signing key."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
apk=Path(sys.argv[1]);sdk=Path(os.environ['ANDROID_HOME'])/'build-tools'/'36.0.0'
signer=subprocess.run([str(sdk/'apksigner'),'verify','--print-certs',str(apk)],check=True,capture_output=True,text=True).stdout
match=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})',signer)
if not match:raise SystemExit('APK signer SHA-256 missing')
badging=subprocess.run([str(sdk/'aapt'),'dump','badging',str(apk)],check=True,capture_output=True,text=True).stdout
package=re.search(r"package: name='([^']+)'",badging)
if not package or package[1]!='org.portlandkollel.kiosk':raise SystemExit('Unexpected complete-app package')
with apk.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
fingerprint=':'.join(match[1][i:i+2].upper() for i in range(0,64,2))
sha1=re.search(r'Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]{40})',signer)
if not sha1:raise SystemExit('APK signer SHA-1 missing')
sha1_text=':'.join(sha1[1][i:i+2].upper() for i in range(0,40,2))
value=dict(signing_certificate_sha1=sha1_text,package_name=package[1],apk_sha256=digest,signing_certificate_sha256=fingerprint,version='0.3.1',source_revision=os.environ.get('SOURCE_REVISION','unknown'),production_signed=False,real_charges_enabled=False)
Path(sys.argv[2]).write_text(json.dumps(value,indent=2)+'\n')
for key in ('package_name','apk_sha256','signing_certificate_sha256','signing_certificate_sha1'):
    print('::notice title=Complete APK '+key+'::'+value[key])
