"""Keep compiler/lint diagnostics readable through GitHub check annotations."""
import pathlib
import subprocess
import sys
result=subprocess.run(['./gradlew',':core:test',':app:assembleDebug',':app:lintDebug','--no-daemon','--console=plain'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
print(result.stdout)
if result.returncode:
    for line in [v for v in result.stdout.splitlines() if v.startswith('e: ') or ' Error:' in v or v.startswith('> Could not')][:30]:
        print('::error::'+line.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
sys.exit(result.returncode)
