#!/usr/bin/env python3
# Puts one nx2android port on the device for Pagurin, from what the port made (<App>.apk and <App>-data/), which are read and never changed.
# Needs only Python 3.9+ and adb: no checkout of the porting tool, no packages.
#   pagurin_import.py <App>.apk <App>-data [--serial <serial>] [--adb <adb>] [--profile <profile folder>] [--check]
# --check: only check the port and the device, push nothing.
# On the device: /sdcard/Android/data/io.github.zod701.pagurin/files/games/<game ID>/ = the data pack, host/ (the APK's libraries), game.apk (its assets: the gamepad theme).
# The pack's env.txt names the port's own package in its paths, so it goes with those paths rewritten. nx2a-data.json goes last: it is what puts the game in the library.
import argparse, hashlib, json, os, shlex, shutil, subprocess, sys, tempfile, time, zipfile
from pathlib import Path

PACKAGE = 'io.github.zod701.pagurin'
FORMAT = 1   # what Pagurin reads from a game folder; the app refuses a game of a later format (Store.SUPPORTED_FORMAT)
CHUNK = 64 << 20   # big files go in pieces joined on the device: one long transfer can wedge adbd
LONG_PATH = 240    # adb on Windows cannot open a path past 260 characters
OWNED = ('host', 'mods', 'RomFS', 'aoc', 'sysdata')   # folders only the port fills (sysdata: the firmware's system data, porter --firmware)

# Host features Pagurin uses, found by text in the host library (a host carries no version number)
FEATURES = {
    'saves': (b'import.txt', b'export.txt', b'result.txt'),   # the save manager the saves tab asks
    'drivers': (b'DM_VK_DRIVER',),   # a GPU driver named in env.txt
}


for _s in (sys.stdout, sys.stderr):   # a console that cannot show a character (cp949 and adb's own messages) prints it replaced, rather than ending the run
    try: _s.reconfigure(errors='replace')
    except (AttributeError, ValueError): pass

def say(*a): print(*a, flush=True)


MARGIN = 256 << 20   # room the device keeps beyond the game

# A game's control data (control.nacp) holds the languages it has: the u32 SupportedLanguageFlag at 0x302C, one bit per language in this order
# (nx2android's nacp.py LANGUAGES). Pagurin offers only these in its game language setting
LANGUAGES = ['en-US', 'en-GB', 'ja', 'fr', 'de', 'es-419', 'es', 'it', 'nl', 'fr-CA', 'pt', 'ru', 'ko', 'zh-Hant', 'zh-Hans', 'pt-BR']

def game_languages(build):
    """The languages in the port's control data, the newest version's, which porter leaves in <title ID>_build/extract/v<version>/; [] when there is none"""
    found = sorted(build.glob('extract/v*/control.nacp'), key=lambda p: int(p.parent.name[1:]) if p.parent.name[1:].isdigit() else -1)
    data = found[-1].read_bytes() if found else b''
    if len(data) < 0x3030: return []
    flags = int.from_bytes(data[0x302C:0x3030], 'little')
    return [c for i, c in enumerate(LANGUAGES) if flags >> i & 1]

def space_short(need, have, biggest, free):
    """What is wrong with the device's free space, or None. need: the port's bytes; have: what the game's folder holds already (a resumed or repeated
    import sends only the rest); biggest: the largest file sent in pieces, which are on the device beside the joined file for a moment; free: bytes free"""
    want = max(need - have, 0) + biggest + MARGIN
    return None if free >= want else f'the device has {free / 2**30:.1f} GB free, and this port needs {want / 2**30:.1f} GB more ({max(need - have, 0) / 2**30:.1f} GB to send)'


def dev_kb(dev, cmd):   # the first number a shell command prints, in KiB, as bytes; 0 when it prints none (a folder that is not there)
    n = dev.sh(cmd + ' 2>/dev/null; true').split()
    return int(n[0]) * 1024 if n and n[0].isdigit() else 0


class Adb:
    def __init__(self, exe, serial):
        self.exe, self.serial = exe, serial
        self.env = dict(os.environ, MSYS_NO_PATHCONV='1')   # Git Bash would turn /sdcard/... into a Windows path

    # adb failing before the command reached the device: its server not answering for a moment (seen every 10-15 minutes in a long push), the device briefly
    # gone or offline. Such a command did nothing, so it is safe to run again; any other failure ends the run, which picks up where it stopped when started again
    UNREACHED = ('cannot connect to daemon', 'daemon still not running', 'daemon not running', 'device offline', 'no devices/emulators found', "' not found")

    def run(self, *a, timeout=600, check=True):
        for wait in (2, 5, 10, 20, 30, 60, 0):
            r = subprocess.run([self.exe] + (['-s', self.serial] if self.serial else []) + [str(x) for x in a], capture_output=True, text=True, errors='replace', timeout=timeout, env=self.env)
            err = (r.stderr or r.stdout).strip()
            if not r.returncode or not wait or not any(m in err for m in self.UNREACHED): break
            say(f'adb did not reach the device ({err.splitlines()[-1][:120] if err else "no message"}): trying again in {wait} s'); time.sleep(wait)
        if check and r.returncode: sys.exit(f'adb {a[0]}: {err[-300:]}')
        return r.stdout

    def sh(self, script, **kw): return self.run('shell', script, **kw)

    def sizes(self, d):   # {relative path: size} of the files under d; {} when d is not there
        q = shlex.quote(d)
        out = self.sh(f"[ -d {q} ] || exit 0; cd {q} && if find /dev/null -printf '' 2>/dev/null; then find . -type f -printf '%s %P\\n'; else find . -type f -exec stat -c '%s %n' {{}} +; fi; echo rc=$?")
        lines = out.splitlines()
        if lines and lines[-1] != 'rc=0': sys.exit(f'listing {d} on the device failed')
        got = {}
        for ln in lines[:-1]:
            n, _, name = ln.partition(' ')
            if n.isdigit(): got[name[2:] if name.startswith('./') else name] = int(n)
        return got

    def md5(self, d, rels):
        got = {}
        for i in range(0, len(rels), 64):
            for ln in self.sh(f'cd {shlex.quote(d)} && md5sum ' + ' '.join(shlex.quote(r) for r in rels[i:i + 64]) + ' 2>/dev/null; true').splitlines():
                h, _, name = ln.partition('  ')
                if len(h) == 32: got[name] = h
        return got

    def push(self, files, dest):   # files into the folder dest, under their own names; through short hard links when a path is too long for adb
        if all(len(str(Path(f).resolve())) < LONG_PATH for f in files):
            return self.run('push', '-Z', *files, dest)
        with tempfile.TemporaryDirectory(prefix='pagurin_') as t:
            short = []
            for f in files:
                s = Path(t) / Path(f).name
                try: os.link(f, s)
                except OSError: shutil.copy2(f, s)
                short.append(s)
            return self.run('push', '-Z', *short, dest)

    def push_big(self, f, dest):   # one file in pieces kept in a hidden folder, so a stopped push resumes
        size = Path(f).stat().st_size; n = (size + CHUNK - 1) // CHUNK
        pieces = f'{os.path.dirname(dest)}/.piece-{hashlib.sha1(dest.encode()).hexdigest()[:12]}'
        self.sh(f'mkdir -p {shlex.quote(pieces)}'); have = self.sizes(pieces)
        with tempfile.TemporaryDirectory(prefix='pagurin_') as t, open(f, 'rb') as src:
            for k in range(n):
                want = min(CHUNK, size - k * CHUNK); name = f'{k:05d}'
                if have.get(name) != want:
                    src.seek(k * CHUNK); (Path(t) / name).write_bytes(src.read(want)); self.run('push', '-Z', Path(t) / name, f'{pieces}/{name}')
                say(f'  {Path(f).name}: piece {k + 1}/{n}')
        q, qp = shlex.quote(dest), shlex.quote(pieces)
        self.sh(f'rm -f {q} && for x in $(ls {qp} | sort); do cat {qp}/$x >> {q} || exit 1; done && rm -rf {qp}', timeout=1800)
        if self.sh(f'stat -c %s {q}').strip() != str(size): sys.exit(f'{Path(f).name}: the joined file has the wrong size')

    def push_tree(self, local, remote, skip=(), keep=(), by_content=lambda rel: False):
        """local folder → remote folder. keep: pushed only when the device has none; by_content: compared by MD5 too, as an engine change can keep a file's size"""
        local = Path(local)
        files = sorted((p.relative_to(local).as_posix(), p) for p in local.rglob('*') if p.is_file())
        have = self.sizes(remote)
        same = [r for r, p in files if r not in skip and r not in keep and by_content(r) and have.get(r) == p.stat().st_size]
        theirs = self.md5(remote, same) if same else {}
        todo = [(r, p) for r, p in files if r not in skip and (r not in have if r in keep else have.get(r) != p.stat().st_size or (r in same and theirs.get(r) != hashlib.md5(p.read_bytes()).hexdigest()))]
        total = sum(p.stat().st_size for _, p in todo)
        say(f'{len(todo):,} files to push, {total / 2**30:.2f} GB ({len(files) - len(todo):,} already there)')
        for d in sorted({os.path.dirname(r) for r, _ in todo}): self.sh(f'mkdir -p {shlex.quote(f"{remote}/{d}" if d else remote)}')
        small = [(r, p) for r, p in todo if p.stat().st_size <= CHUNK]; done = 0
        by_dir = {}
        for r, p in small: by_dir.setdefault(os.path.dirname(r), []).append(p)
        for d, ps in by_dir.items():
            for i in range(0, len(ps), 32):
                self.push(ps[i:i + 32], f'{remote}/{d}/' if d else f'{remote}/'); done += sum(p.stat().st_size for p in ps[i:i + 32])
                say(f'{done / 2**30:7.2f} / {total / 2**30:.2f} GB')
        for r, p in todo:
            if p.stat().st_size > CHUNK: self.push_big(p, f'{remote}/{r}'); done += p.stat().st_size; say(f'{done / 2**30:7.2f} / {total / 2**30:.2f} GB')


def profile_key(folders, file, key):   # a key of the port's profile: the one made beside the port, else one given
    for folder in folders:
        if folder and (folder / file).is_file():
            for ln in (folder / file).read_text(encoding='utf-8').splitlines():
                if ln.startswith(key + '\t'): return ln.split('\t')[1].strip()
            return None
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('apk', type=Path); ap.add_argument('data', type=Path)
    ap.add_argument('--serial'); ap.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    ap.add_argument('--profile', type=Path, help="the port's profile folder, when it is not in <title ID>_build/profile/ beside the APK")
    ap.add_argument('--check', action='store_true', help='check the port and the device only')
    a = ap.parse_args()

    # ---- the port ----
    problems, warnings = [], []
    man_file = a.data / 'nx2a-data.json'
    if not man_file.is_file(): sys.exit(f'{a.data}: no nx2a-data.json: not a port\'s data pack')
    man = json.loads(man_file.read_text(encoding='utf-8'))
    for need in ('mods', 'RomFS'):
        if not (a.data / need).is_dir(): problems.append(f'the data pack has no {need}/')
    for need in ('shaders.dmsv', 'env.txt'):
        if not (a.data / need).is_file(): problems.append(f'the data pack has no {need}')
    with zipfile.ZipFile(a.apk) as z:
        libs = {Path(n).name: z.read(n) for n in z.namelist() if n.startswith('lib/arm64-v8a/') and n.endswith('.so')}
    host = libs.get('libdmhost.so')
    if host is None: problems.append('the APK has no arm64 host library (lib/arm64-v8a/libdmhost.so)')
    elif b'ANativeActivity_onCreate' not in host: problems.append('the host library has no entry point Pagurin can start')
    features = sorted(f for f, marks in FEATURES.items() if host and all(m in host for m in marks))
    if host and 'saves' not in features: warnings.append('this host has no save manager: the saves tab cannot export or import (port the game again with a newer tool)')
    if host and 'drivers' not in features: warnings.append('this host does not take a GPU driver by name: drivers chosen in Pagurin do not reach it')
    title = man.get('title_id', '')
    if not title.isalnum(): problems.append('nx2a-data.json has no usable game ID')
    profile = [a.profile, a.apk.parent / f'{title}_build/profile' / title]
    languages = game_languages(a.apk.parent / f'{title}_build')
    say(f'{man.get("name", "?")} {man.get("display", "")} ({title}): host features {", ".join(features) or "none"}; languages {", ".join(languages) or "not found"}')

    # ---- the device ----
    dev = Adb(a.adb, a.serial)
    if not Path(a.adb).exists() and not shutil.which(a.adb): sys.exit(f'adb not found ({a.adb}): give it with --adb')
    states = [ln.split('\t') for ln in dev.run('devices').splitlines()[1:] if '\t' in ln]
    ready = [s for s, st in states if st == 'device']
    if not a.serial:
        if len(ready) != 1: sys.exit('no device connected' if not ready else f'several devices: choose one with --serial ({", ".join(ready)})')
        dev.serial = ready[0]
    abis = dev.sh('getprop ro.product.cpu.abilist').strip()
    if 'arm64-v8a' not in abis: problems.append(f'the device is not arm64 ({abis})')
    if 'bcn' in man.get('requires', []):
        gles = dev.sh('dumpsys SurfaceFlinger 2>/dev/null | grep -m1 "^GLES:"; true').lower()
        if any(v in gles for v in ('mali', 'powervr', 'immortalis')): problems.append('the game needs BC-compressed textures, which this GPU does not read')
    if f'package:{PACKAGE}' not in dev.sh(f'pm list packages {PACKAGE}').split(): problems.append(f'{PACKAGE} is not installed on the device')
    sizes = [p.stat().st_size for p in a.data.rglob('*') if p.is_file()]
    short = space_short(sum(sizes) + a.apk.stat().st_size + sum(len(b) for b in libs.values()), dev_kb(dev, f'du -sk /sdcard/Android/data/{PACKAGE}/files/games/{title}'),
                        max([s for s in sizes if s > CHUNK], default=0), dev_kb(dev, "df -k /sdcard | tail -n 1 | awk '{print $4}'"))
    if short: problems.append(short)

    for w in warnings: say('warning: ' + w)
    if problems: sys.exit('cannot add this port:\n  ' + '\n  '.join(problems))
    if a.check: say('the port and the device are fine'); return

    # ---- the transfer ----
    files = f'/sdcard/Android/data/{PACKAGE}/files'; remote = f'{files}/games/{title}'
    # The app makes its files folder at its first start: one the shell makes first lacks the app's group underneath FUSE, and the app cannot read it
    dev.sh(f'[ -d {files} ] || am start -W -n {PACKAGE}/.MainActivity >/dev/null 2>&1; true')
    for _ in range(60):
        if dev.sh(f'[ -d {files} ] && echo yes; true').strip() == 'yes': break
        time.sleep(0.5)
    else: sys.exit(f'{PACKAGE} did not make {files}')

    by_content = lambda rel: not rel.startswith(('RomFS/', 'aoc/'))   # the port's own outputs; the game's files change only with its version, and hashing them takes minutes
    with tempfile.TemporaryDirectory(prefix='pagurin_') as t:
        stage = Path(t)
        (stage / 'host').mkdir()
        for name, b in libs.items(): (stage / 'host' / name).write_bytes(b)
        shutil.copyfile(a.apk, stage / 'game.apk')
        old = f'/sdcard/Android/data/{man["package"]}/files'
        (stage / 'env.txt').write_text((a.data / 'env.txt').read_text(encoding='utf-8').replace(old, remote), encoding='utf-8', newline='\n')
        rows = [ln for ln in (a.data / 'settings.txt').read_text(encoding='utf-8').splitlines() if '=' in ln and not ln.startswith('ui=')] if (a.data / 'settings.txt').is_file() else []
        if not any(r.startswith('docked=') for r in rows):   # the mode the host takes from the profile when nothing is set: docked unless op_mode says handheld
            rows.append('docked=' + ('0' if profile_key(profile, 'game.tsv', 'op_mode') == 'handheld' else '1'))
        (stage / 'settings.default.txt').write_text(''.join(r + '\n' for r in rows), encoding='utf-8', newline='\n')   # what the settings screen shows and resets to
        dev.push_tree(stage, remote, by_content=by_content)
        dev.push_tree(a.data, remote, skip={'env.txt', 'nx2a-data.json'}, keep={'settings.txt'}, by_content=by_content)
        # What an older port left in the folders the port owns: the game would still find its files, and the host would load its libraries (as porter's install.py does)
        stale = [r for r in dev.sizes(remote) if r.split('/', 1)[0] in OWNED and not (a.data / r).is_file() and not (stage / r).is_file()]
        for i in range(0, len(stale), 64): dev.sh(f'cd {shlex.quote(remote)} && rm -f ' + ' '.join(shlex.quote(r) for r in stale[i:i + 64]))
        if stale: say(f'{len(stale):,} files of an older port removed')
        status = profile_key(profile, 'android.tsv', 'status') or 'experimental'
        man.update(package=PACKAGE, status=status, pagurin={'format': FORMAT, 'features': features, 'warnings': warnings, **({'languages': languages} if languages else {})})
        (stage / 'nx2a-data.json').write_text(json.dumps(man, indent=2), encoding='utf-8')
        dev.run('push', '-Z', stage / 'nx2a-data.json', f'{remote}/nx2a-data.json')
    say(f'{man["name"]} is in {remote}: it shows in Pagurin\'s library')


if __name__ == '__main__': main()
