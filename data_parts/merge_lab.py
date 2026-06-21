"""Clone 后运行此脚本，自动合并分片为 Lab.dat"""
import os, glob

parts = sorted(glob.glob(os.path.join(os.path.dirname(__file__), "Lab.dat.part*")))
out = os.path.join(os.path.dirname(__file__), "..", "Lab.dat")

with open(out, "wb") as f:
    for p in parts:
        with open(p, "rb") as chunk:
            f.write(chunk.read())
        print(f"Merged {os.path.basename(p)}")

print(f"Done → {out} ({os.path.getsize(out)} bytes)")
