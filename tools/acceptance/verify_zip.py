#!/usr/bin/env python3
"""M8 验收：校验导出 zip 完整性。
- 列出 zip 条目
- 每个 files/<relPath> 计算 SHA-256，与基线 DB 的 contentHash 比对
- 解析 library.json 的 schemaVersion / 目录数 / 文档数
"""
import sqlite3
import hashlib
import zipfile
import json
import sys

db = sys.argv[1] if len(sys.argv) > 1 else "tools/acceptance/before/myreader.db"
zip_path = sys.argv[2] if len(sys.argv) > 2 else "tools/acceptance/myreader-backup.zip"

conn = sqlite3.connect(db)
rows = conn.execute("SELECT relPath, contentHash, fileSize FROM documents").fetchall()
conn.close()
db_map = {r[0]: (r[1], r[2]) for r in rows}

z = zipfile.ZipFile(zip_path)
names = z.namelist()
print(f"=== zip 条目 ({len(names)}) ===")
for n in names:
    print("  " + n)

mismatches = 0
written = 0
print("\n=== files/ 哈希比对 (zip SHA-256 vs DB contentHash) ===")
for n in names:
    if n.startswith("files/") and not n.endswith("/"):
        rel = n[len("files/"):]
        data = z.read(n)
        sha = hashlib.sha256(data).hexdigest()
        dbhash, dbsize = db_map.get(rel, (None, None))
        ok = (dbhash == sha)
        if not ok:
            mismatches += 1
        print(f"  {rel}: match={ok} zip_sha={sha[:16]}.. db_hash={(str(dbhash) or 'None')[:16]}.. "
              f"zip_size={len(data)} db_size={dbsize}")
        written += 1

print(f"\n文件条目: {written}, 哈希不匹配: {mismatches}")

if "library.json" in names:
    m = json.loads(z.read("library.json"))
    print(f"library.json: schemaVersion={m['schemaVersion']} folders={len(m['folders'])} "
          f"documents={len(m['documents'])} appVersion={m.get('appVersion')}")
    # 校验每个文档的 relPath 白名单
    bad = [d['relPath'] for d in m['documents'] if not __import__('re').match(r'^[0-9]+\.[a-zA-Z0-9]{1,10}$', d['relPath'])]
    print(f"relPath 白名单违规: {bad if bad else '无'}")

sys.exit(1 if mismatches else 0)
