"""그림에 박혀 있는 체크무늬(가짜 투명)·흰 배경을 실제 투명으로 바꾸고 여백을 잘라 축소한다.

이미지 가장자리와 연결된 밝은 무채색 픽셀만 지운다(그림 안쪽의 흰색은 유지).
사용법: python tools/remove_checker_bg.py 입력 출력.png [최대높이]
"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage

src, dst = sys.argv[1], sys.argv[2]
max_h = int(sys.argv[3]) if len(sys.argv) > 3 else 800

orig = Image.open(src).convert("RGBA")
a0 = np.asarray(orig.getchannel("A"))
rgb = np.asarray(orig.convert("RGB")).astype(np.int16)
already = (a0 == 0).mean() > 0.05   # 이미 실제 투명 배경이면 지우지 않고 여백만 정리
mx, mn = rgb.max(axis=2), rgb.min(axis=2)
cand = (mx - mn <= 14) & (mn >= 175)            # 밝은 회색/흰색 = 체크무늬 후보
lab, _ = ndimage.label(cand)
edge = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
bg = np.isin(lab, edge[edge > 0])
bg = ndimage.binary_closing(bg, iterations=1) | bg

# 가장자리 1px 부드럽게: 배경과 맞닿은 밝은 픽셀은 반투명
if already:
    alpha, bg = a0.copy(), a0 == 0
else:
    alpha = np.where(bg, 0, 255).astype(np.uint8)
    ring = ndimage.binary_dilation(bg, iterations=1) & ~bg
    soft = ring & (mn >= 150) & (mx - mn <= 30)
    alpha[soft] = 110

img = Image.fromarray(np.dstack([rgb.astype(np.uint8), alpha]), "RGBA")
bbox = img.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()   # 거의 투명한 잡티는 제외
img = img.crop((max(0, bbox[0] - 4), max(0, bbox[1] - 4), min(img.width, bbox[2] + 4), min(img.height, bbox[3] + 4)))
k = min(1, max_h / img.height)
if k < 1:
    img = img.resize((round(img.width * k), round(img.height * k)), Image.LANCZOS)
img.save(dst, optimize=True)
print(dst, img.size, "이미 투명 배경 - 여백만 정리" if already else f"배경 제거 {bg.mean():.0%}")
