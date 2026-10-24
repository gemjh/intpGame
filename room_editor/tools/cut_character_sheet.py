"""설계/골라주세요_캐릭터.png 의 "2등신 (32×48px)" 패널에서 캐릭터 스프라이트 20장을 잘라 만든다.

시트에는 정면·측면(왼쪽)·뒷모습과 왼쪽으로 걷는 프레임 4장만 있어서:
- 오른쪽 = 왼쪽 좌우반전
- 왼쪽 걷기 = [걷기2, 측면, 걷기3, 측면]
- 앞/뒤 걷기 = 비스듬한 걷기 프레임(1, 4)과 그 반전을 정지 포즈 사이에 끼운 임시 구성
모든 프레임은 키 48px 로 줄여 40×48 캔버스에 발 기준으로 맞춘다 (모자 챙·포니테일이 32px 를 넘음).
요구: Pillow. 사용법(저장소 루트에서): python room_editor/tools/cut_character_sheet.py
이후 python room_editor/tools/build_character_sprites.py 와 python room_editor/build.py 를 다시 실행한다.
"""
from collections import deque
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT_DIRS = [ROOT / "room_editor/assets/character", ROOT / "intp_room_android/app/src/main/assets/character"]
im = Image.open(ROOT / "설계/골라주세요_캐릭터.png").convert("RGBA")
W,H=im.size; px=im.load()
def isbg(c): return abs(c[0]-247)+abs(c[1]-239)+abs(c[2]-237) < 30
def comps(box):
    x0,y0,x1,y1=box; seen=set(); out=[]
    for y in range(y0,y1):
        for x in range(x0,x1):
            if (x,y) in seen or isbg(px[x,y]): continue
            q=deque([(x,y)]); seen.add((x,y)); pts=[]
            while q:
                a,b=q.popleft(); pts.append((a,b))
                for c,d in ((a+1,b),(a-1,b),(a,b+1),(a,b-1)):
                    if x0<=c<x1 and y0<=d<y1 and (c,d) not in seen and not isbg(px[c,d]):
                        seen.add((c,d)); q.append((c,d))
            if len(pts)>800:
                xs=[p[0] for p in pts]; ys=[p[1] for p in pts]
                out.append((min(xs),min(ys),max(xs)+1,max(ys)+1,len(pts)))
    return sorted(out)
top=comps((76,120,500,322)); bot=comps((60,420,480,550))

def cutout(box):
    x0,y0,x1,y1=box[:4]; x0-=2;y0-=2;x1+=2;y1+=2
    c=im.crop((x0,y0,x1,y1)); p=c.load(); w,h=c.size
    q=deque([(x,y) for x in range(w) for y in (0,h-1)]+[(x,y) for y in range(h) for x in (0,w-1)]); seen=set(q)
    while q:
        a,b=q.popleft(); p[a,b]=(0,0,0,0)
        for cc,d in ((a+1,b),(a-1,b),(a,b+1),(a,b-1)):
            if 0<=cc<w and 0<=d<h and (cc,d) not in seen and isbg(p[cc,d]):
                seen.add((cc,d)); q.append((cc,d))
    # 테두리에 남은 배경색 안티앨리어싱(밝은 테두리) 제거
    for _ in range(3):
        kill=[]
        for y in range(h):
            for x in range(w):
                v=p[x,y]
                if v[3]==0: continue
                near=any(0<=a<w and 0<=b<h and p[a,b][3]==0 for a,b in ((x+1,y),(x-1,y),(x,y+1),(x,y-1)))
                if near and abs(v[0]-247)+abs(v[1]-239)+abs(v[2]-237) < 110 and max(v[:3])-min(v[:3]) < 40:
                    kill.append((x,y))
        for x,y in kill: p[x,y]=(0,0,0,0)
    return c.crop(c.getbbox())

def shrink(c, height=48):
    w=max(1,round(c.width*height/c.height))
    s=c.convert('RGBa').resize((w,height),Image.BOX).convert('RGBA')
    a=s.getchannel('A').point(lambda v:255 if v>=110 else 0); s.putalpha(a)
    return s

CW,CH=40,48
def place(s):
    out=Image.new('RGBA',(CW,CH)); out.paste(s,((CW-s.width)//2,CH-s.height),s); return out

front,side,back=[cutout(b) for b in top]
walks=[cutout(b) for b in bot]
mir=lambda i:i.transpose(Image.FLIP_LEFT_RIGHT)
F,S,B=[place(shrink(x)) for x in (front,side,back)]
Wk=[place(shrink(x)) for x in walks]
frames={'idle_front':F,'idle_back':B,'idle_left':S,'idle_right':mir(S)}
for d,seq in {'left':[Wk[1],S,Wk[2],S],'front':[Wk[0],F,mir(Wk[0]),F],'back':[Wk[3],B,mir(Wk[3]),B]}.items():
    for i,f in enumerate(seq,1): frames[f'walk_{d}_{i}']=f
for i in range(1,5): frames[f'walk_right_{i}']=mir(frames[f'walk_left_{i}'])
for d in OUT_DIRS:
    for k, v in frames.items():
        v.save(d / f"{k}.png")
print(f"{len(frames)}장 저장: " + ", ".join(str(d.relative_to(ROOT)) for d in OUT_DIRS))
