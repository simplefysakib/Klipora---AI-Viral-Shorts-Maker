# desktop twin of FaceAnalyzer.java: same frames (ffmpeg fps=5, 640 wide jpg), same model file, same tiling rule,
# same features -> FaceTrack samples text
import sys, glob, os, subprocess, time, shutil
sys.path.insert(0,'/tmp/mp/pylib21')
import numpy as np
from PIL import Image
import mediapipe as mp
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision
src, start, dur, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
FPS=5
d = out + '_frames'; shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
subprocess.run(['ffmpeg','-nostdin','-loglevel','error','-y','-ss',str(start),'-t',str(dur),'-i',src,
  '-map','0:v:0','-vf','fps=%d,scale=640:-2'%FPS,'-q:v','5',d+'/f%05d.jpg',
  '-map','0:a:0','-ac','1','-ar','8000','-f','s16le',out+'.pcm'],check=True)
pcm = np.fromfile(out+'.pcm', dtype=np.int16).astype(np.float32)/32768
opts = vision.FaceLandmarkerOptions(base_options=mpt.BaseOptions(model_asset_path='/tmp/mp/face_landmarker.task'),
    running_mode=vision.RunningMode.IMAGE, num_faces=4, min_face_detection_confidence=0.4, min_face_presence_confidence=0.4)
lm = vision.FaceLandmarker.create_from_options(opts)
pl = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(base_options=mpt.BaseOptions(model_asset_path='/tmp/mp/pose_landmarker_lite.task'),running_mode=vision.RunningMode.IMAGE, num_poses=4, min_pose_detection_confidence=0.4, min_pose_presence_confidence=0.4))
def heads_of(arr, W, H):
    r = pl.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(arr)))
    res=[]
    for L in r.pose_landmarks:
        cx=sum(L[k].x for k in range(11))/11; cy=sum(L[k].y for k in range(11))/11
        ear=((L[7].x-L[8].x)**2*W*W+(L[7].y-L[8].y)**2*H*H)**.5
        sh=((L[11].x-L[12].x)**2*W*W+(L[11].y-L[12].y)**2*H*H)**.5
        hw=max(ear*1.1, sh*0.42, 8.0)/W; hh=hw*W*1.3/H
        res.append([cx-hw/2, cy-hh/2, cx+hw/2, cy+hh/2, -1.0])
    return res
def faces_of(arr, x0, W, H, tw, th):
    r = lm.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(arr)))
    res=[]
    for L in r.face_landmarks:
        xs=[(x0+p.x*tw)/W for p in L]; ys=[p.y*th/H for p in L]
        fh=((L[10].x*tw-L[152].x*tw)**2+(L[10].y*th-L[152].y*th)**2)**.5
        mo=((L[13].x*tw-L[14].x*tw)**2+(L[13].y*th-L[14].y*th)**2)**.5/max(fh,1e-6)
        res.append([min(xs),min(ys),max(xs),max(ys),mo])
    return res
files=sorted(glob.glob(d+'/*.jpg')); prev=None; lines=[]; t0=time.time(); tiles=0
for i,f in enumerate(files):
    im=Image.open(f).convert('RGB'); W,H=im.size; a=np.array(im)
    fs=faces_of(a,0,W,H,W,H)
    small = not any(x[3]-x[1] > 0.3 for x in fs)
    if W > H*1.2 and small and (not fs or i % 2 == 0):
        tiles+=1
        for g in heads_of(a, W, H):
            gx=(g[0]+g[2])/2; gy=(g[1]+g[3])/2; dup=False
            for e in fs:
                ew=e[2]-e[0]; eh=e[3]-e[1]
                if abs((e[0]+e[2])/2-gx) < 0.8*ew+0.02 and abs((e[1]+e[3])/2-gy) < 0.8*eh+0.03: dup=True
            if not dup: fs.append(g)
    th=np.array(im.convert('L').resize((32,18), Image.BILINEAR),dtype=np.float32)
    cut=0.0 if prev is None else float(np.mean(np.abs(th-prev))); prev=th
    c=int(i*8000/FPS); hw=int(8000/FPS/2); seg=pcm[max(0,c-hw):c+hw]
    rms=float(np.sqrt(np.mean(seg**2))) if len(seg) else 0.0
    lines.append('%.5f %.2f'%(rms,cut)+''.join('|%.4f %.4f %.4f %.4f %.4f'%tuple(x) for x in fs))
open(out+'.txt','w').write('\n'.join(lines)+'\n')
print(out, len(files),'frames', 'tiled', tiles, 'ms/frame %.1f'%((time.time()-t0)*1000/max(1,len(files))), 'srcsize', a.shape)
