@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo [1/4] Bagimliliklar kuruluyor...
python -m pip install -r requirements.txt pyinstaller || goto :err
echo [2/4] Simge uretiliyor...
if not exist assets\app.ico python tools\make_icon.py
echo [3/4] Testler calistiriliyor...
python tests\test_smoke.py || goto :err
echo [4/4] PyInstaller...
python -m PyInstaller --noconfirm --clean --onefile --windowed --name OBD2TaniPro ^
  --icon assets\app.ico ^
  --add-data "data;data" ^
  --hidden-import bleak.backends.winrt.client --hidden-import bleak.backends.winrt.scanner ^
  --collect-submodules bleak ^
  main.py || goto :err
echo.
echo Tamam: dist\OBD2TaniPro.exe
exit /b 0
:err
echo HATA olustu.
exit /b 1
