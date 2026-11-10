#!/usr/bin/env python3
"""Inspect an existing APK without changing packaging; stdlib only, no payload dumps."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import zipfile


def analyze(path):
    path = Path(path)
    groups = defaultdict(lambda: [0, 0, 0])
    abis = defaultdict(lambda: [0, 0, 0])
    with zipfile.ZipFile(path) as apk:
        entries = [item for item in apk.infolist() if not item.is_dir()]
        for item in entries:
            name = item.filename
            group = ('native libraries' if name.startswith('lib/') else
                     'DEX bytecode' if name.endswith('.dex') else
                     'assets' if name.startswith('assets/') else
                     'Android resources' if name.startswith('res/') or name == 'resources.arsc' else 'other')
            for bucket in [groups[group]] + ([abis[name.split('/')[1]]] if name.startswith('lib/') and len(name.split('/')) > 2 else []):
                bucket[0] += item.compress_size
                bucket[1] += item.file_size
                bucket[2] += 1
        def rows(buckets):
            return [dict(name=k, compressed_bytes=v[0], uncompressed_bytes=v[1], entries=v[2])
                    for k, v in sorted(buckets.items(), key=lambda pair: pair[1][0], reverse=True)]
        return dict(apk=path.name, sha256=hashlib.file_digest(path.open('rb'), 'sha256').hexdigest(),
                    apk_bytes=path.stat().st_size, compressed_entry_bytes=sum(e.compress_size for e in entries),
                    uncompressed_entry_bytes=sum(e.file_size for e in entries), groups=rows(groups), abis=rows(abis),
                    largest_files=[dict(name=e.filename, compressed_bytes=e.compress_size, uncompressed_bytes=e.file_size)
                                   for e in sorted(entries, key=lambda e:e.compress_size, reverse=True)[:20]],
                    limits='ZIP entry sizes, not installed size or exact Maven attribution. Packaging unchanged.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--github',action='store_true')
    args=parser.parse_args()
    report=analyze(args.apk)
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    if args.github:
        # Safe build metadata only; no APK contents or credentials.
        print('::notice title=APK size report::'+json.dumps(report,separators=(',',':')))


if __name__=='__main__':main()
