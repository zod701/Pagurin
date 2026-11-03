#!/usr/bin/env python3
# Builds pagurin_import.py into one executable that runs without Python: out/pagurin-import(.exe), for the system it is built on (PyInstaller makes
# no cross builds: build on Windows for Windows). It makes its own virtual environment in out/import-exe/ with a pinned PyInstaller, so nothing is
# installed into the Python that runs it. Needs Python 3.9+ (a python.org one on Windows: MSYS2's is not one PyInstaller supports) and the network
# on the first run.   build_import_exe.py
import os, subprocess, sys, venv
from pathlib import Path

PYINSTALLER = 'pyinstaller==6.11.1'
ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / 'out' / 'import-exe'
VENV = WORK / 'venv'
PY = VENV / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')

def run(*args): subprocess.run([str(a) for a in args], check=True)

if not PY.is_file(): venv.EnvBuilder(with_pip=True).create(VENV)
run(PY, '-m', 'pip', 'install', '--quiet', '--disable-pip-version-check', PYINSTALLER)
run(PY, '-m', 'PyInstaller', '--onefile', '--console', '--clean', '--noconfirm', '--name', 'pagurin-import',
    '--distpath', ROOT / 'out', '--workpath', WORK / 'build', '--specpath', WORK, ROOT / 'tools' / 'pagurin_import.py')
print(f"made {ROOT / 'out' / ('pagurin-import.exe' if os.name == 'nt' else 'pagurin-import')}")
