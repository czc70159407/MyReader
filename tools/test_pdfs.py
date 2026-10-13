#!/usr/bin/env python3
"""MyReader 离线验收夹具：生成 + 校验。

把 M3-M7 真机验收用到的测试 PDF 固化成可复现脚本，避免每次手工用 pikepdf 现造。
生成（generate）五类夹具，校验（verify）它们满足验收对属性的断言。

夹具清单（对齐 AGENTS.md 验收证据）：
  demo_test.pdf      —— 基础 3 页 PDF（导入/书架缩略图/「3 页」标注）
  outline_test.pdf   —— 12 页 + 两级大纲（M4 大纲跳转验收）
  corrupt_test.pdf   —— 损坏 PDF（M3 拒收 / M4 -2 标记）
  encrypted_test.pdf —— 密码 "1234" 加密（M3/M4/M6 加密标记 + 首开兜底）
  long_test.pdf      —— 40 页（M7 长文档快速连滑验收）

依赖: pypdf, reportlab  ——  pip install pypdf reportlab
用法:
  python tools/test_pdfs.py generate [--out tools/fixtures]
  python tools/test_pdfs.py verify   [--out tools/fixtures]
  python tools/test_pdfs.py selftest            # 生成到临时目录并立即校验
"""
from __future__ import annotations

import argparse
import os
import tempfile

from pypdf import PdfReader, PdfWriter
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.pdfgen import canvas
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate

ENCRYPT_PASSWORD = "1234"

# (文件名, 期望页数, 是否加密) —— verify 据此断言
EXPECT = {
    "demo_test.pdf": {"pages": 3, "encrypted": False},
    "outline_test.pdf": {"pages": 12, "encrypted": False, "outline": True},
    "corrupt_test.pdf": {"pages": None, "encrypted": False, "corrupt": True},
    "encrypted_test.pdf": {"pages": 3, "encrypted": True},
    "long_test.pdf": {"pages": 40, "encrypted": False},
}


# ----------------------------- 生成 -----------------------------

def _basic_flowable_pdf(path: str, pages: int, title: str) -> None:
    """用 Platypus 生成 N 页基础 PDF（每页一段文字）。"""
    styles = getSampleStyleSheet()
    doc = SimpleDocTemplate(path, pagesize=letter)
    story = []
    for i in range(pages):
        story.append(Paragraph(f"{title} — page {i + 1}", styles["Heading2"]))
        story.append(Paragraph("MyReader offline acceptance fixture.", styles["Normal"]))
        story.append(PageBreak())
    # 最后一页多出的 PageBreak 不会产生空白页
    doc.build(story)


def make_demo(out: str) -> str:
    p = os.path.join(out, "demo_test.pdf")
    _basic_flowable_pdf(p, 3, "Demo Test")
    return p


def make_long(out: str) -> str:
    p = os.path.join(out, "long_test.pdf")
    _basic_flowable_pdf(p, 40, "Long Test")
    return p


def make_outline(out: str) -> str:
    """用 canvas 显式写两级大纲（SimpleDocTemplate 不会自动生成书签）。"""
    p = os.path.join(out, "outline_test.pdf")
    c = canvas.Canvas(p, pagesize=letter)
    plan = [
        ("Section 1 Introduction", 0),
        ("1.1 Overview", 1),
        ("1.2 Usage", 1),
        ("Section 2 Details", 0),
        ("2.1 Architecture", 1),
        ("2.2 Data Flow", 1),
        ("Section 3 Appendix", 0),
        ("3.1 FAQ", 1),
        ("3.2 Glossary", 1),
    ]
    key_of = {
        "Section 1 Introduction": "s1",
        "Section 2 Details": "s2",
        "Section 3 Appendix": "s3",
    }
    page = 0
    for title, level in plan:
        c.drawString(72, 720, title)
        key = key_of.get(title, title.lower().replace(" ", "_"))
        c.bookmarkPage(key)
        c.addOutlineEntry(title, key, level=level)
        c.showPage()
        page += 1
    while page < 12:
        c.drawString(72, 720, f"Filler page {page + 1}")
        c.showPage()
        page += 1
    c.save()
    return p


def make_corrupt(out: str) -> str:
    """不可恢复的损坏 PDF：有 %PDF 头但无任何对象/xref/trailer。
    早期版本含 `1 0 obj<<>>endobj`，fitz 1.28.2 的 repair 会据此重建出文档而"打开成功"，
    导致「损坏拒收」与插桩用例 open_corruptPdf_throwsCorruptDocument 不符预期（M9 真机验收暴露）。
    改为纯高字节填充（不含 obj/xref/trailer 等 ASCII 令牌）——pypdf 与 fitz 均无法解析/修复。"""
    p = os.path.join(out, "corrupt_test.pdf")
    with open(p, "wb") as f:
        f.write(b"%PDF-1.4\n")
        f.write(b"% intentionally corrupt: no objects / xref / trailer\n")
        f.write(b"\x80\xff" * 200)   # 高字节填充，规避 fitz repair 的对象扫描
    return p


def make_encrypted(out: str) -> str:
    """先生成基础 PDF，再用 pypdf 加密（用户密码 1234）。"""
    src = os.path.join(out, ".encrypted_src.pdf")
    _basic_flowable_pdf(src, 3, "Encrypted Test")
    dst = os.path.join(out, "encrypted_test.pdf")
    reader = PdfReader(src)
    writer = PdfWriter()
    writer.append(reader)
    writer.encrypt(user_password=ENCRYPT_PASSWORD, owner_password=ENCRYPT_PASSWORD)
    with open(dst, "wb") as f:
        writer.write(f)
    os.remove(src)
    return dst


def generate(out: str) -> list[str]:
    os.makedirs(out, exist_ok=True)
    paths = [
        make_demo(out),
        make_outline(out),
        make_corrupt(out),
        make_encrypted(out),
        make_long(out),
    ]
    return paths


# ----------------------------- 校验 -----------------------------

def _flatten_outline(outline):
    items = []
    for it in outline:
        if isinstance(it, list):
            items.extend(_flatten_outline(it))
        else:
            items.append(it)
    return items


def verify(out: str) -> tuple[int, int, list[str]]:
    """返回 (passed, total, messages)。校验失败不抛异常，便于汇总。"""
    passed = 0
    total = 0
    messages: list[str] = []

    def check(name: str, cond: bool, detail: str) -> None:
        nonlocal passed, total
        total += 1
        mark = "PASS" if cond else "FAIL"
        if cond:
            passed += 1
        messages.append(f"  [{mark}] {name}: {detail}")

    for fname, expect in EXPECT.items():
        path = os.path.join(out, fname)
        if not os.path.exists(path):
            check(fname, False, "文件不存在")
            continue

        # 损坏：pypdf 必须无法解析
        if expect.get("corrupt"):
            try:
                PdfReader(path)
                check(fname, False, "pypdf 竟然成功解析了（应为损坏）")
            except Exception:
                check(fname, True, "pypdf 解析失败（符合损坏预期）")
            continue

        # 加密：无密码打开必须报加密；用正确密码可解密且页数符合
        if expect.get("encrypted"):
            raises = False
            try:
                r = PdfReader(path)
                encrypted = r.is_encrypted
            except Exception:
                raises = True
                encrypted = True
            ok_open = raises or encrypted
            check(fname, ok_open, "无密码打开被拒（加密）" )
            try:
                r2 = PdfReader(path)
                rc = r2.decrypt(ENCRYPT_PASSWORD)
                actual_pages = len(r2.pages)
                ok_decrypt = (rc in (1, 2)) and actual_pages == expect["pages"]
                check(fname, ok_decrypt,
                      f"密码 {ENCRYPT_PASSWORD} 解密成功且页数={actual_pages}（期望 {expect['pages']}）")
            except Exception as e:
                check(fname, False, f"解密异常: {e}")
            continue

        # 普通 PDF：页数符合
        try:
            r = PdfReader(path)
            actual = len(r.pages)
            check(fname, actual == expect["pages"], f"页数={actual}（期望 {expect['pages']}）")
        except Exception as e:
            check(fname, False, f"解析异常: {e}")
            continue

        # 大纲：outline_test 必须有嵌套大纲
        if expect.get("outline"):
            try:
                items = _flatten_outline(r.outline)
                top = [x for x in r.outline if not isinstance(x, list)]
                nested = any(isinstance(x, list) for x in r.outline)
                ok = len(items) >= 9 and len(top) == 3 and nested
                check(fname, ok,
                      f"大纲条目={len(items)} 顶层={len(top)} 含嵌套={nested}")
            except Exception as e:
                check(fname, False, f"大纲解析异常: {e}")

    return passed, total, messages


# ----------------------------- 入口 -----------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="MyReader 测试 PDF 生成/校验工具")
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
