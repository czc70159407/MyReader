import base64, sqlite3, os, sys

BASE = os.path.dirname(os.path.abspath(__file__))
DBDIR = os.path.join(BASE, "m9", "db")

def decode():
    for src, dst in [("main.b64", "myreader.db"), ("wal.b64", "myreader.db-wal")]:
        raw = open(os.path.join(DBDIR, src), encoding="utf-16").read()
        txt = "".join(raw.split())
        open(os.path.join(DBDIR, dst), "wb").write(base64.b64decode(txt) if txt else b"")

def main():
    decode()
    main_sz = os.path.getsize(os.path.join(DBDIR, "myreader.db"))
    wal_sz = os.path.getsize(os.path.join(DBDIR, "myreader.db-wal"))
    print(f"main={main_sz} wal={wal_sz}")
    c = sqlite3.connect(os.path.join(DBDIR, "myreader.db"))
    rows = list(c.execute(
        "SELECT fileType,progressType,pageCount,currentPage,progressPayload,lastReadAt,needsPassword FROM documents"))
    print(f"rows={len(rows)}")
    for r in rows:
        print("ROW", r)

if __name__ == "__main__":
    main()
