"""Rotiva simgelerini tek geometriden üretir: assets/icon.svg, icon-512.png, app.ico ve Android vektör kaynakları."""
import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, root)
from PySide6.QtGui import QGuiApplication  # noqa: E402
from app.ui import icon as I  # noqa: E402

app = QGuiApplication([])
assets = os.path.join(root, "assets")
os.makedirs(assets, exist_ok=True)
with open(os.path.join(assets, "icon.svg"), "w", encoding="utf-8") as f:
    f.write(I.icon_svg())
I.make_pixmap(512).save(os.path.join(assets, "icon-512.png"), "PNG")
I.write_ico(os.path.join(assets, "app.ico"))

# ---- Android (varsa)
res = os.path.join(root, "android", "app", "src", "main", "res")
if os.path.isdir(res):
    NS = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'
    d = I.DOT

    def grad(attr, a, b, x1=21, y1=21, x2=87, y2=87):
        return (f'<aapt:attr name="android:{attr}"><gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
                f'android:endX="{x2}" android:endY="{y2}"><item android:offset="0" android:color="{a}"/>'
                f'<item android:offset="1" android:color="{b}"/></gradient></aapt:attr>')

    def vec(body, w=108):
        return (f'<?xml version="1.0" encoding="utf-8"?>\n<vector {NS} android:width="{w}dp" android:height="{w}dp" '
                f'android:viewportWidth="108" android:viewportHeight="108">\n{body}</vector>\n')

    def circle(color):
        cx, cy, r = d
        return (f'<path android:fillColor="{color}" android:pathData="M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0z"/>')

    mark = f"{I.STEM} {I.BOWL} {I.LEG}"
    stroke = (f'<path android:pathData="{mark}" android:strokeWidth="{I.STROKE_W}" android:strokeLineCap="round" '
              f'android:strokeLineJoin="round" android:fillColor="#00000000">{grad("strokeColor", I.ACC1, I.ACC2)}</path>\n')
    ridge = f'<path android:pathData="{I.RIDGE}">{grad("fillColor", "#383B82F6", "#3822D3EE")}</path>\n'
    dr = os.path.join(res, "drawable")
    os.makedirs(dr, exist_ok=True)
    w = lambda n, t: open(os.path.join(dr, n), "w", encoding="utf-8", newline="\n").write(t)
    w("ic_launcher_background.xml", vec(f'<path android:pathData="M0,0h108v108h-108z">{grad("fillColor", I.BG1, I.BG2, 0, 0, 108, 108)}</path>\n'))
    w("ic_launcher_foreground.xml", vec(ridge + stroke + circle(I.WARM) + "\n"))
    mono = (f'<path android:pathData="{I.RIDGE}" android:fillColor="#000000" android:fillAlpha="0.3"/>\n'
            f'<path android:pathData="{mark}" android:strokeWidth="{I.STROKE_W}" android:strokeLineCap="round" '
            f'android:strokeLineJoin="round" android:strokeColor="#000000" android:fillColor="#00000000"/>\n' + circle("#000000") + "\n")
    w("ic_launcher_monochrome.xml", vec(mono))
    w("ic_stat_rotiva.xml", vec(mono.replace('android:fillAlpha="0.3"', 'android:fillAlpha="0.0"'), 24))
    # açılış ekranı: yalnızca işaret, yuvarlak maskeye sığması için %85 küçültülmüş
    w("ic_splash.xml", vec('<group android:scaleX="0.85" android:scaleY="0.85" android:pivotX="54" android:pivotY="54">\n'
                           + stroke + circle(I.WARM) + '\n</group>\n'))
    ml = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(ml, exist_ok=True)
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(ml, n), "w", encoding="utf-8", newline="\n").write(adaptive)
    old = os.path.join(dr, "ic_launcher.xml")
    if os.path.exists(old):
        os.remove(old)
    rel = os.path.join(root, "android", "release")
    os.makedirs(rel, exist_ok=True)
    I.make_pixmap(512).save(os.path.join(rel, "icon-preview.png"), "PNG")
print("simgeler yazildi")
