#!/usr/bin/env python3
"""M8 验收：打印/对比 MyReader Room 数据库基线。

用法:
  python tools/acceptance/verify_db.py <db_path>            # 打印该库 folders + documents
  python tools/acceptance/verify_db.py <before.db> <after.db>  # 对比两份库，输出差异

注意：db_path 所在目录需同时放 -wal / -shm（WAL 模式）才能读全数据。
"""
import sqlite3
import sys
import os


def snapshot(db_path):
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA busy_timeout=5000")
    cur = conn.cursor()
    # 探测表
    tables = [r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
    out = {"folders": [], "documents": []}
    if "folders" in tables:
        cols = [d[1] for d in cur.execute("PRAGMA table_info(folders)").fetchall()]
        cur.execute(f"SELECT {','.join(cols)} FROM folders ORDER BY id")
        out["folders"] = [dict(zip(cols, row)) for row in cur.fetchall()]
    if "documents" in tables:
        cols = [d[1] for d in cur.execute("PRAGMA table_info(documents)").fetchall()]
        cur.execute(f"SELECT {','.join(cols)} FROM documents ORDER BY id")
        out["documents"] = [dict(zip(cols, row)) for row in cur.fetchall()]
    conn.close()
    return out


def print_snapshot(tag, snap):
    print(f"===== {tag} =====")
    print(f"[folders] 共 {len(snap['folders'])} 条")
    for f in snap["folders"]:
        print(f"  id={f.get('id')} name={f.get('name')!r} parentId={f.get('parentId')}")
    print(f"[documents] 共 {len(snap['documents'])} 条")
    for d in snap["documents"]:
        print(f"  id={d.get('id')} title={d.get('title')!r} folderId={d.get('folderId')} "
              f"relPath={d.get('relPath')} needsPassword={d.get('needsPassword')} "
              f"pageCount={d.get('pageCount')} currentPage={d.get('currentPage')} "
              f"scrollOffsetY={d.get('scrollOffsetY')} lastReadAt={d.get('lastReadAt')} "
              f"thumb={d.get('thumbnailRelPath')}")
    print()


def diff(before, after):
    problems = []
    # documents 按 id 比较核心字段
    bdocs = {d["id"]: d for d in before["documents"]}
    adocs = {d["id"]: d for d in after["documents"]}
    if set(bdocs) != set(adocs):
        problems.append(f"文档 ID 集合不一致: before={set(bdocs)} after={set(adocs)}")
    fields = ["folderId", "title", "relPath", "needsPassword", "pageCount",
              "currentPage", "scrollOffsetY", "lastReadAt", "thumbnailRelPath"]
    for did in sorted(set(bdocs) & set(adocs)):
        for fld in fields:
            if bdocs[did].get(fld) != adocs[did].get(fld):
                problems.append(
                    f"文档 {did} 字段 {fld} 不一致: before={bdocs[did].get(fld)!r} after={adocs[did].get(fld)!r}")
    # folders 按 id 比较
    bfol = {f["id"]: f for f in before["folders"]}
    afol = {f["id"]: f for f in after["folders"]}
    if set(bfol) != set(afol):
        problems.append(f"目录 ID 集合不一致: before={set(bfol)} after={set(afol)}")
    for fid in sorted(set(bfol) & set(afol)):
        for fld in ["name", "parentId"]:
            if bfol[fid].get(fld) != afol[fid].get(fld):
                problems.append(
                    f"目录 {fid} 字段 {fld} 不一致: before={bfol[fid].get(fld)!r} after={afol[fid].get(fld)!r}")
    return problems


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) == 1:
        print_snapshot("DB", snapshot(args[0]))
    elif len(args) == 2:
        before = snapshot(args[0])
        after = snapshot(args[1])
        print_snapshot("BEFORE", before)
        print_snapshot("AFTER", after)
        probs = diff(before, after)
        print("===== 对比结果 =====")
        if not probs:
            print("PASS: 目录树/文档/进度/加密/页数 全部一致 [OK]")
        else:
            print(f"FAIL: 发现 {len(probs)} 处差异:")
            for p in probs:
                print("  - " + p)
        sys.exit(0 if not probs else 1)
    else:
        print(__doc__)
        sys.exit(2)
