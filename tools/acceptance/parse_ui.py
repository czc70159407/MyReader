#!/usr/bin/env python3
"""解析 uiautomator dump 的 ui.xml，打印有文字/描述或可见的节点 bounds，便于坐标点击。"""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1] if len(sys.argv) > 1 else "tools/acceptance/ui.xml"
tree = ET.parse(path)
root = tree.getroot()

print("=== 含文字/描述的节点 ===")
for n in root.iter("node"):
    txt = n.get("text", "") or ""
    desc = n.get("content-desc", "") or ""
    if txt or desc:
        print(f"  text={txt!r} desc={desc!r} clickable={n.get('clickable')} bounds={n.get('bounds')}")

print("\n=== 所有 clickable=true 的节点 (前40) ===")
cnt = 0
for n in root.iter("node"):
    if n.get("clickable") == "true":
        txt = n.get("text", "") or ""
        desc = n.get("content-desc", "") or ""
        print(f"  text={txt!r} desc={desc!r} bounds={n.get('bounds')}")
        cnt += 1
        if cnt >= 40:
            break
print(f"(共 {cnt} 个 clickable 显示前40)")
