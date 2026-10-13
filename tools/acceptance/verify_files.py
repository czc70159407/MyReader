#!/usr/bin/env python3
"""M8 验收：校验恢复后的物理文件 SHA-256 与基线 DB contentHash 一致。"""
import sqlite3
import hashlib
import tarfile
import sys

db = sys.argv[1]
lib_tar = sys.argv[2]
thumb_tar = sys.argv[3]

conn = sqlite3.connect(db)
rows = conn.execute("SELECT relPath, contentHash FROM documents").fetchall()
conn.close()
db_map = {r[0]: r[1] for r in rows}

mism = 0
cnt = 0
with tarfile.open(lib_tar) as t:
    for m in t.getmembers():
        if m.isfile():
            data = t.extractfile(m).read()
            sha = hashlib.sha256(data).hexdigest()
            rel = m.name.split("/", 1)[1] if "/" in m.name else m.name
            ok = db_map.get(rel) == sha
            if not ok:
                mism += 1
            print(f"  {rel}: match={ok} size={len(data)}")
            cnt += 1
print(f"\nlibrary 文件: {cnt}, 哈希不匹配: {mism}")

with tarfile.open(thumb_tar) as t:
    thumbs = [m.name for m in t.getmembers() if m.isfile()]
print(f"thumb 文件: {len(thumbs)} -> {thumbs}")

sys.exit(1 if mism else 0)
