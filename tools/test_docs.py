#!/usr/bin/env python3
"""MyReader M9 离线验收夹具：多格式（EPUB / CBZ / XPS）生成 + 校验。

与 tools/test_pdfs.py 同 CLI 契约（generate / verify / selftest），共用 check() 助手风格。
**零第三方 Python 依赖**（纯 stdlib：zipfile / zlib / struct）——CBZ 内 PNG 与 XPS 的 OPC 包
均手写构造，避免引入 Pillow 等。

夹具清单（对齐 docs/m9-plan.md §5.2）：
  epub_test.epub      —— 3 章 EPUB（zip：mimetype STORED 首条目 + container.xml + OPF + NCX + 3 XHTML）
  comic_test.cbz      —— 5 页漫画（zip：5 个手写最小 PNG，按名自然序）
  xps_test.xps        —— 2 页 XPS（zip：FixedDocumentSequence.fdseq + .rels + 2 FixedPage XAML）
  unsupported_test.docx —— 伪造扩展名（导入期应被拒收）

能否被 MuPDF 打开由真机插桩用例 MuPdfMultiFormatEngineTest 判定（本脚本只验结构合法性）。

用法:
  python tools/test_docs.py generate [--out tools/fixtures]
  python tools/test_docs.py verify   [--out tools/fixtures]
  python tools/test_docs.py selftest
"""
from __future__ import annotations

import argparse
import os
import struct
import tempfile
import zipfile
import zlib

# (文件名, 期望属性) —— verify 据此断言
EXPECT = {
    "epub_test.epub": {"kind": "epub", "chapters": 3},
    "comic_test.cbz": {"kind": "cbz", "images": 5},
    "xps_test.xps": {"kind": "xps", "pages": 2},
    "unsupported_test.docx": {"kind": "unsupported"},
}


# ----------------------------- 底层：最小 PNG -----------------------------

def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def make_png(width: int, height: int, rgb: tuple) -> bytes:
    """手写最小合法 PNG（RGB、8bit、无交错）。"""
    r, g, b = rgb
    row = bytes([0]) + bytes([r, g, b]) * width          # 每行：filter=0 + width*3 像素
    raw = row * height
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        sig
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", zlib.compress(raw, 9))
        + _png_chunk(b"IEND", b"")
    )


# ----------------------------- 生成：EPUB -----------------------------

def make_epub(out: str) -> str:
    p = os.path.join(out, "epub_test.epub")
    container = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
        '<rootfiles><rootfile full-path="OEBPS/content.opf" '
        'media-type="application/oebps-package+xml"/></rootfiles></container>'
    )
    opf = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        '<dc:title>MyReader Epub Fixture</dc:title><dc:language>en</dc:language>'
        '<dc:identifier id="bookid">urn:uuid:myreader-epub-test</dc:identifier></metadata>'
        '<manifest>'
        '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
        '<item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>'
        '<item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>'
        '<item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>'
        '</manifest>'
        '<spine toc="ncx">'
        '<itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/>'
        '</spine></package>'
    )
    ncx = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">'
        '<navMap>'
        '<navPoint id="n1"><navLabel><text>Chapter 1</text></navLabel><content src="ch1.xhtml"/></navPoint>'
        '<navPoint id="n2"><navLabel><text>Chapter 2</text></navLabel><content src="ch2.xhtml"/></navPoint>'
        '<navPoint id="n3"><navLabel><text>Chapter 3</text></navLabel><content src="ch3.xhtml"/></navPoint>'
        '</navMap></ncx>'
    )

    def chapter(n: int) -> str:
        # 每章塞多段文字，使重排后约占数页（≈12 页总量）
        body = "".join(
            f"<p>Chapter {n} paragraph {i}: MyReader epub acceptance fixture text for reflow layout.</p>"
            for i in range(40)
        )
        return (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<html xmlns="http://www.w3.org/1999/xhtml"><head>'
            f"<title>Chapter {n}</title></head><body><h1>Chapter {n}</h1>{body}</body></html>"
        )

    with zipfile.ZipFile(p, "w") as z:
        # mimetype 必须为首条目且 STORED（EPUB OPC 规范）
        zi = zipfile.ZipInfo("mimetype")
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, "application/epub+zip")
        z.writestr("META-INF/container.xml", container)
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/toc.ncx", ncx)
        for n in (1, 2, 3):
            z.writestr(f"OEBPS/ch{n}.xhtml", chapter(n))
    return p


# ----------------------------- 生成：CBZ -----------------------------

def make_cbz(out: str) -> str:
    p = os.path.join(out, "comic_test.cbz")
    palette = [(220, 60, 60), (60, 120, 220), (60, 180, 90), (230, 200, 60), (150, 80, 200)]
    with zipfile.ZipFile(p, "w") as z:
        for i in range(5):
            # 零填充命名保证自然序 = 阅读序
            z.writestr(f"page{i + 1:02d}.png", make_png(120, 160, palette[i]))
    return p


# ----------------------------- 生成：XPS -----------------------------

def make_xps(out: str) -> str:
    p = os.path.join(out, "xps_test.xps")

    def fixed_page(n: int) -> str:
        return (
            '<FixedPage xmlns="http://schemas.microsoft.com/xps/2005/06" '
            'Width="300" Height="400">'
            '<Canvas>'
            f'<Glyphs OriginX="40" OriginY="80" FontRenderingEmSize="24" '
            f'Fill="#000000" UnicodeString="XPS Page {n}"/>'
            f'<Glyphs OriginX="40" OriginY="140" FontRenderingEmSize="14" '
            f'Fill="#333333" UnicodeString="MyReader xps acceptance fixture"/>'
            '</Canvas></FixedPage>'
        )

    content_types = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="fdseq" ContentType="application/vnd.ms-package.xps-fixeddocumentsequence+xml"/>'
        '<Default Extension="fdoc" ContentType="application/vnd.ms-package.xps-fixeddocument+xml"/>'
        '<Default Extension="fpage" ContentType="application/vnd.ms-xps.page+xml"/>'
        '</Types>'
    )
    root_rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="RID1" '
        'Type="http://schemas.microsoft.com/xps/2005/06/fixeddocumentsequence" '
        'Target="FixedDocSeq.fdseq"/></Relationships>'
    )
    fdseq = (
        '<FixedDocumentSequence xmlns="http://schemas.microsoft.com/xps/2005/06">'
        '<DocumentReference Source="Documents/1/FixedDoc.fdoc"/></FixedDocumentSequence>'
    )
    fdseq_rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="RID1" '
        'Type="http://schemas.microsoft.com/xps/2005/06/fixeddocument" '
        'Target="Documents/1/FixedDoc.fdoc"/></Relationships>'
    )
    fdoc = (
        '<FixedDocument xmlns="http://schemas.microsoft.com/xps/2005/06">'
        '<PageContent Source="Pages/1.fpage"/><PageContent Source="Pages/2.fpage"/></FixedDocument>'
    )
    fdoc_rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="P1" Type="http://schemas.microsoft.com/xps/2005/06/page" Target="Pages/1.fpage"/>'
        '<Relationship Id="P2" Type="http://schemas.microsoft.com/xps/2005/06/page" Target="Pages/2.fpage"/>'
        '</Relationships>'
    )
    with zipfile.ZipFile(p, "w") as z:
        # OPC 约定：[Content_Types].xml 为首条目
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", root_rels)
        z.writestr("FixedDocSeq.fdseq", fdseq)
        z.writestr("_rels/FixedDocSeq.fdseq.rels", fdseq_rels)
        z.writestr("Documents/1/FixedDoc.fdoc", fdoc)
        z.writestr("Documents/1/_rels/FixedDoc.fdoc.rels", fdoc_rels)
        z.writestr("Documents/1/Pages/1.fpage", fixed_page(1))
        z.writestr("Documents/1/Pages/2.fpage", fixed_page(2))
    return p


# ----------------------------- 生成：不支持的格式 -----------------------------

def make_unsupported(out: str) -> str:
    p = os.path.join(out, "unsupported_test.docx")
    with open(p, "wb") as f:
        # 伪 docx：zip 头 + 垃圾内容；导入期应因扩展名未注册被拒（不依赖内容可解析）
        f.write(b"PK\x03\x04" + b"not-a-real-docx " * 4)
    return p


def generate(out: str) -> list:
    os.makedirs(out, exist_ok=True)
    return [make_epub(out), make_cbz(out), make_xps(out), make_unsupported(out)]


# ----------------------------- 校验 -----------------------------

def verify(out: str):
    passed = 0
    total = 0
    messages = []

    def check(name, cond, detail):
        nonlocal passed, total
        total += 1
        if cond:
            passed += 1
        messages.append(f"  [{'PASS' if cond else 'FAIL'}] {name}: {detail}")

    for fname, exp in EXPECT.items():
        path = os.path.join(out, fname)
        if not os.path.exists(path):
            check(fname, False, "文件不存在")
            continue

        kind = exp["kind"]
        if kind == "unsupported":
            with open(path, "rb") as f:
                head = f.read(4)
            check(fname, head == b"PK\x03\x04", "伪 docx 具有 zip 头（导入期应拒收）")
            continue

        try:
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
                if kind == "epub":
                    check(fname + " [zip]", "mimetype" in names and "META-INF/container.xml" in names,
                          f"条目含 mimetype/container.xml（共 {len(names)} 条目）")
                    info0 = z.infolist()[0]
                    check(fname + " [mimetype]",
                          info0.filename == "mimetype" and info0.compress_type == zipfile.ZIP_STORED,
                          "mimetype 为首条目且 STORED")
                    chapters = [n for n in names if n.endswith(".xhtml")]
                    check(fname + " [chapters]", len(chapters) == exp["chapters"],
                          f"XHTML 章节数={len(chapters)}（期望 {exp['chapters']}）")
                    ncx = z.read("OEBPS/toc.ncx").decode("utf-8", "ignore")
                    check(fname + " [toc]", ncx.count("<navPoint") == exp["chapters"],
                          f"NCX navPoint={ncx.count('<navPoint')}")
                elif kind == "cbz":
                    imgs = [n for n in names if n.lower().endswith(".png")]
                    check(fname + " [images]", len(imgs) == exp["images"],
                          f"PNG 条目数={len(imgs)}（期望 {exp['images']}）")
                    first = z.read(imgs[0])
                    ok = first[:8] == b"\x89PNG\r\n\x1a\n" and first[-8:-4] == b"IEND"
                    check(fname + " [png]", ok, "首图为合法 PNG（签名+IEND）")
                elif kind == "xps":
                    check(fname + " [fdseq]", "FixedDocSeq.fdseq" in names,
                          "含 FixedDocSeq.fdseq")
                    check(fname + " [ct]", "[Content_Types].xml" in names and "_rels/.rels" in names,
                          "含 [Content_Types].xml + _rels/.rels")
                    pages = [n for n in names if n.endswith(".fpage")]
                    check(fname + " [pages]", len(pages) == exp["pages"],
                          f"FixedPage 数={len(pages)}（期望 {exp['pages']}）")
        except Exception as e:  # noqa: BLE001
            check(fname, False, f"解析异常: {e}")

    return passed, total, messages


# ----------------------------- 入口 -----------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="MyReader 多格式测试夹具生成/校验工具")
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("generate", "verify"):
        sp = sub.add_parser(name)
        sp.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "fixtures"))
    sub.add_parser("selftest")
    args = parser.parse_args()

    if args.cmd == "generate":
        paths = generate(args.out)
        print(f"已生成 {len(paths)} 个夹具到 {args.out}:")
        for p in paths:
            print(f"  - {os.path.basename(p)}")
        return 0

    if args.cmd == "verify":
        passed, total, msgs = verify(args.out)
        print(f"校验目录: {args.out}")
        print("\n".join(msgs))
        print(f"\n汇总: {passed}/{total} 通过")
        return 0 if passed == total else 1

    if args.cmd == "selftest":
        with tempfile.TemporaryDirectory() as tmp:
            generate(tmp)
            passed, total, msgs = verify(tmp)
            print("\n".join(msgs))
            print(f"\nselftest 汇总: {passed}/{total} 通过")
            return 0 if passed == total else 1

    return 2


if __name__ == "__main__":
    raise SystemExit(main())
