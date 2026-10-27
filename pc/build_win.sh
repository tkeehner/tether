#!/bin/sh
# Build dist/Tether-PC-win64.zip: a portable Windows bundle (embedded CPython 3.12 + wheels for win_amd64).
set -e; cd "$(dirname "$0")"; B=build/win; mkdir -p $B dist
[ -f $B/python-3.12.10-embed-amd64.zip ] || curl -sL -o $B/python-3.12.10-embed-amd64.zip https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip
pip download -q --only-binary=:all: --platform win_amd64 --python-version 3.12 --implementation cp -d $B/wheels cryptography zeroconf
rm -rf $B/Tether-PC; mkdir -p $B/Tether-PC/python/Lib/site-packages $B/Tether-PC/app
unzip -q $B/python-3.12.10-embed-amd64.zip -d $B/Tether-PC/python
for w in $B/wheels/*.whl; do unzip -q -o $w -d $B/Tether-PC/python/Lib/site-packages; done
printf 'python312.zip\r\n.\r\nLib\\site-packages\r\n..\\app\r\nimport site\r\n' > $B/Tether-PC/python/python312._pth
cp -r plink $B/Tether-PC/app/; rm -rf $B/Tether-PC/app/plink/__pycache__
cp README.md $B/Tether-PC/README.md
printf '@echo off\r\n"%%~dp0python\\python.exe" -m plink %%*\r\n' > $B/Tether-PC/plink.bat
printf '@echo off\r\n"%%~dp0python\\python.exe" -m plink mcp %%*\r\n' > $B/Tether-PC/plink-mcp.bat
printf '@echo off\r\nstart "" /b "%%~dp0python\\pythonw.exe" -m plink agent\r\n' > $B/Tether-PC/plink-agent.bat
rm -f dist/Tether-PC-win64.zip; python3 -c "import shutil;shutil.make_archive('dist/Tether-PC-win64','zip','$B','Tether-PC')"
ls -la dist
