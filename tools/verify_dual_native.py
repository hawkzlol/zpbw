"""Independently clean/test/build both native targets; collect a fresh verified pair.

No runtime launch, install or publication. A built pair is BUILT_UNQUALIFIED.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
import zipfile
from datetime import datetime, timezone
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent.parent
TARGETS = ('26.2', '26.3')
PRIVATE_PREFIXES = ('dev/zpew/', 'dev/zpbw/control/', 'dev/zpbw/traffic/', 'com/hawkslol/blackwell/', 'org/lwjgl/', 'net/minecraft/')
CONFIGURATIONS = ('compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath')
NATIVE_CLASSES = ('net/minecraft/client/Minecraft.class', 'net/minecraft/client/player/LocalPlayer.class',
                  'net/minecraft/client/multiplayer/ClientPacketListener.class', 'net/minecraft/network/Connection.class',
                  'com/mojang/blaze3d/platform/InputConstants.class')
PINS = {'java': 25, 'kotlin': '2.4.10', 'loom': '1.17.11', 'gradle': '9.6.0',
        'fabricLanguageKotlin': '1.13.13+kotlin.2.4.10'}
WRAPPER_SHA256 = 'bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01'
SHA256 = re.compile(r'^[0-9a-fA-F]{64}$')


def relative_name(value):
    if not isinstance(value, str) or not value or '\\' in value or ':' in value or '\0' in value:
        raise ValueError('Noncanonical/traversing relative path')
    parts = value.split('/')
    if any(p in ('', '.', '..') for p in parts) or Path(value).is_absolute():
        raise ValueError('Noncanonical/traversing relative path')
    return value


def regular(path, owner=None):
    if not isinstance(path, (str, Path)):
        raise ValueError('Missing required file path')
    path = Path(path)
    if '..' in path.parts or any(':' in part for part in path.parts[1:]) or not path.is_absolute():
        raise ValueError('Noncanonical file path')
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise ValueError('Missing required regular file: ' + str(path)) from error
    if resolved != path or not path.is_file() or path.is_symlink():
        raise ValueError('Linked/aliased or nonregular file')
    if owner is not None and not resolved.is_relative_to(Path(owner).resolve()):
        raise ValueError('File escaped expected owner')
    return path


def lane_root(root, definitions, target):
    item = definitions['targets'][target]
    expected = 'versions/mc' + target.replace('.', '_')
    if item.get('projectRoot') != expected or item.get('sourceRoot') != 'src/native' + target.replace('.', '_'):
        raise ValueError('Wrong/traversing native lane root')
    relative_name(item['projectRoot']); relative_name(item['sourceRoot'])
    if item.get('testRoot') != 'src/native' + target.replace('.', '_') + 'Test':
        raise ValueError('Wrong/traversing native test root')
    return root / expected


def properties(path):
    result = {}
    for line in regular(path).read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        key, separator, value = line.partition('=')
        if not separator or key.strip() in result:
            raise ValueError('Invalid/duplicate pinned property')
        result[key.strip()] = value.strip().replace('\\:', ':')
    return result


def check_pins(root, definitions, initialize_locks=False):
    if any(definitions.get(key) != value for key, value in PINS.items()) or set(definitions.get('targets', {})) != set(TARGETS):
        raise ValueError('Wrong Java/Kotlin/Loom/Gradle/FLK native build pins')
    wrapper = properties(root / 'gradle/wrapper/gradle-wrapper.properties')
    if wrapper.get('distributionUrl') != 'https://services.gradle.org/distributions/gradle-9.6.0-bin.zip' or \
            wrapper.get('distributionSha256Sum') != WRAPPER_SHA256:
        raise ValueError('Wrong/missing pinned wrapper distribution/checksum')
    if properties(root / 'gradle.properties').get('fabric_kotlin_version') != PINS['fabricLanguageKotlin']:
        raise ValueError('Wrong FLK property pin')
    script = regular(root / 'gradle/zpbw-native.gradle').read_text(encoding='utf-8')
    if re.findall(r'jvmToolchain\s*\(\s*(\d+)\s*\)', script) != ['25'] or re.findall(r'options\.release\s*=\s*(\d+)', script) != ['25']:
        raise ValueError('Wrong Java25 compiler/toolchain pin')
    settings = [root / 'settings.gradle']
    verification = {}
    for target in TARGETS:
        lane = lane_root(root, definitions, target); item = definitions['targets'][target]
        if item.get('loader') != ('0.19.3' if target == '26.2' else '0.19.5') or \
                item.get('fabricApi') != ('0.155.2+26.2' if target == '26.2' else '0.165.0+26.3'):
            raise ValueError('Wrong native loader/API pin')
        settings.append(lane / 'settings.gradle')
        if not initialize_locks:
            lock = regular(lane / 'gradle.lockfile', lane)
            if not lock.read_text(encoding='utf-8').strip():
                raise ValueError('Missing/empty native dependency lock')
            xml = regular(lane / 'gradle/verification-metadata.xml', lane)
            checksums = [element.attrib.get('value', '') for element in ElementTree.parse(xml).getroot().iter()
                         if element.tag.rsplit('}', 1)[-1] == 'sha256']
            if not checksums or any(not SHA256.fullmatch(value) for value in checksums):
                raise ValueError('Missing/malformed native dependency verification SHA256')
            verification[target] = {'path': str(xml), 'sha256': digest(xml)}
    for path in settings:
        text = regular(path, root).read_text(encoding='utf-8')
        for plugin, expected in [('net.fabricmc.fabric-loom', PINS['loom']), ('org.jetbrains.kotlin.jvm', PINS['kotlin'])]:
            versions = re.findall(r'id\s+[\'\"]' + re.escape(plugin) + r'[\'\"]\s+version\s+[\'\"]([^\'\"]+)[\'\"]', text)
            if versions != [expected]:
                raise ValueError('Wrong/missing pinned plugin in ' + str(path))
    return {'pins': dict(PINS), 'wrapperSha256': WRAPPER_SHA256, 'verification': verification}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(',', ':')).encode()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def source_routes(root, definitions, target):
    lane = definitions['targets'][target]
    relative_name(lane['sourceRoot'])
    for name in lane['requiredNativeFiles'] + lane['replacements']:
        relative_name(name)
    shared, native = root / 'src/main', root / lane['sourceRoot']
    required, replacements = set(lane['requiredNativeFiles']), set(lane['replacements'])
    found = {p.relative_to(native).as_posix(): p for p in native.rglob('*') if p.is_file()}
    if not required <= found.keys():
        raise ValueError('Missing required native source: ' + str(sorted(required - found.keys())))
    selected, fqcn = {}, {}
    for base, owner in ((shared, 'shared'), (native, target)):
        for path in sorted(base.rglob('*')):
            if not path.is_file():
                continue
            regular(path, root)
            rel = path.relative_to(base).as_posix()
            if rel in selected and (rel not in replacements or base != native):
                raise ValueError('Unapproved native replacement: ' + rel)
            if base == native and rel not in required:
                raise ValueError('Unlisted native implementation: ' + rel)
            selected[rel] = {'path': path.relative_to(root).as_posix(), 'sha256': digest(path), 'owner': owner}
    actual = {r for r in selected if (shared / r).is_file() and (native / r).is_file()}
    if actual != replacements:
        raise ValueError('Native replacement manifest does not match routes')
    for rel, entry in selected.items():
        path = root / entry['path']
        if path.suffix not in ('.java', '.kt'):
            continue
        text = path.read_text(encoding='utf-8')
        package = re.search(r'^package\s+([\w.]+)', text, re.M)
        if not package:
            raise ValueError('Production source without package: ' + rel)
        names = re.findall(r'^(?:(?:public|internal|private|abstract|final|sealed|data|enum|annotation|open|value|expect|actual)\s+)*(?:class|interface|object)\s+(\w+)', text, re.M)
        for name in names:
            key = package[1] + '.' + name
            if key in fqcn:
                raise ValueError('Duplicate FQCN: ' + key)
            fqcn[key] = rel
    return selected


def fingerprint(root):
    files = []
    for part in ('src', 'gradle', 'versions', 'tools'):
        for p in sorted((root / part).rglob('*')):
            if not p.is_file() or any(x in p.relative_to(root).parts for x in ('build', '.gradle', '__pycache__')):
                continue
            files.append([p.relative_to(root).as_posix(), digest(p)])
    for name in ('native-targets.json', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'LICENSE'):
        files.append([name, digest(root / name)])
    return hashlib.sha256(canonical(files)).hexdigest(), files


def inspect_jar(path, target, version):
    expected = f'zpbw-mc{target}-{version}.jar'
    if path.name != expected or path.is_symlink() or not path.is_file():
        raise ValueError('Wrong native install artifact name or missing JAR')
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)) or names.count('fabric.mod.json') != 1:
            raise ValueError('Duplicate archive entries/metadata')
        meta = json.loads(jar.read('fabric.mod.json'))
        if meta['id'] != 'zpbw' or meta['version'] != version or meta['depends']['minecraft'] != target:
            raise ValueError('Wrong product/native metadata')
        loader = '0.19.3' if target == '26.2' else '0.19.5'
        if meta.get('environment') != 'client' or meta.get('license') != 'MIT' or meta.get('authors') != ['hawkslol']:
            raise ValueError('Wrong product environment/license/author metadata')
        if meta['depends'].get('java') != '>=25' or meta['depends'].get('fabricloader') != '>=' + loader:
            raise ValueError('Wrong Java/loader requirement')
        api = '0.155.2+26.2' if target == '26.2' else '0.165.0+26.3'
        kotlin = '1.13.13+kotlin.2.4.10'
        if meta['depends'].get('fabric-api') not in (api, '>=' + api) or meta['depends'].get('fabric-language-kotlin') not in (kotlin, '>=' + kotlin):
            raise ValueError('Wrong API/Kotlin requirement')
        if meta['entrypoints'] != {'client': ['com.hawkslol.zpbw.ZpbwClient']} or meta['mixins'] != ['zpbw.mixins.json']:
            raise ValueError('Wrong entrypoint/Mixin metadata')
        if 'com/hawkslol/zpbw/ZpbwClient.class' not in names:
            raise ValueError('Missing entrypoint class')
        registry = json.loads(jar.read('zpbw.mixins.json'))
        if not registry.get('required') or registry['injectors']['defaultRequire'] != 1:
            raise ValueError('Weakened native Mixin contract')
        for mixin in registry['client']:
            if (registry['package'] + '.' + mixin).replace('.', '/') + '.class' not in names:
                raise ValueError('Unpackaged registered Mixin')
        for name in names:
            relative_name(name.rstrip('/'))
            if name.lower().startswith(tuple(p.lower() for p in PRIVATE_PREFIXES)) or any(s in name for s in ('Harness', 'labfixture', '.env', 'accounts.json', 'run-receipt')):
                raise ValueError('Private/native dependency leaked into product: ' + name)
            if name.endswith('.class'):
                header = jar.read(name)[:8]
                if len(header) != 8 or header[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(header[6:8], 'big') != 69:
                    raise ValueError('Non-Java25 class in native product: ' + name)
        identity = jar.read('com/hawkslol/zpbw/nativebridge/NativeIdentity.class')
        other = '26.3' if target == '26.2' else '26.2'
        if target.encode() not in identity or other.encode() in identity:
            raise ValueError('Compiled native identity mismatch')
        for api in ('com/hawkslol/zpbw/api/ExternalPredictionLease.class', 'dev/zpbw/candidate/api/ExternalPredictionLease.class'):
            if api not in names:
                raise ValueError('Missing public prediction lease API')
    return {'name': expected, 'sha256': digest(path), 'metadata': meta, 'classCount': sum(n.endswith('.class') for n in names)}


def check_effective(root, lane, target, routes, run=None):
    report_path = regular(lane / 'build/reports/native/effective-sources.json', lane)
    report = json.loads(report_path.read_text())
    if report['target'] != target or set(report['sources']) != set(routes) or report.get('productRoot') != str(root):
        raise ValueError('Actual effective source routes differ from verified selection')
    if run is not None and report.get('buildRunId') != run:
        raise ValueError('Actual effective report current run mismatch')
    if not re.match(r'^25(?:[.+-]|$)', str(report.get('java', ''))):
        raise ValueError('Actual compiler is not Java25')
    for rel, item in report['sources'].items():
        expected = routes[rel]
        if regular(item['path'], root) != regular(root / expected['path'], root) or item['sha256'] != expected['sha256'] or item.get('owner') != expected['owner']:
            raise ValueError('Actual compiler source owner/hash mismatch: ' + rel)
    opposite = root / ('versions/mc26_3' if target == '26.2' else 'versions/mc26_2')
    if set(report.get('classpaths', {})) != set(CONFIGURATIONS) or set(report.get('modules', {})) != set(CONFIGURATIONS):
        raise ValueError('All four actual classpath/module inventories required')
    hashes, jars, classpaths = {}, {}, {}
    def artifact(item, field='path'):
        p = regular(item[field])
        if p.is_relative_to(opposite) or not SHA256.fullmatch(str(item.get('sha256', ''))):
            raise ValueError('Opposite native/stale classpath artifact')
        if p not in hashes:
            hashes[p] = digest(p)
        if hashes[p] != item['sha256'].lower():
            raise ValueError('Opposite native/stale classpath artifact')
        return p
    for config, items in report['classpaths'].items():
        if not isinstance(items, list) or not items:
            raise ValueError('Empty actual classpath inventory')
        actual = []
        for item in items:
            p = artifact(item); actual.append(p)
            if p.suffix.lower() == '.jar' and p not in jars:
                with zipfile.ZipFile(p) as jar:
                    contents = jar.namelist()
                    if len(contents) != len(set(contents)):
                        raise ValueError('Duplicate classpath archive entry')
                    owned = set(NATIVE_CLASSES).intersection(contents)
                    try:
                        version = json.loads(jar.read('version.json')) if owned else None
                    except (KeyError, ValueError) as error:
                        raise ValueError('Missing/invalid native class owner version ID') from error
                    if owned and not isinstance(version, dict):
                        raise ValueError('Missing/invalid native class owner version ID')
                    if owned and version.get('id') != target:
                        raise ValueError('Actual native class owner version ID mismatch')
                    for name in owned:
                        header = jar.read(name)[:8]
                        if len(header) != 8 or header[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(header[6:8], 'big') != 69:
                            raise ValueError('Wrong native class owner Java bytecode')
                    jars[p] = owned
        if len(set(actual)) != len(actual):
            raise ValueError('Duplicate actual classpath artifact')
        classpaths[config] = actual
    owners = report.get('nativeOwners', {})
    if set(owners) != set(NATIVE_CLASSES):
        raise ValueError('Exact five native class owners required')
    for name, owner in owners.items():
        p = artifact(owner)
        if p not in classpaths['compileClasspath'] or name not in jars.get(p, set()):
            raise ValueError('Native owner does not own actual compiler class')
        for config, actual in classpaths.items():
            if [candidate for candidate in actual if name in jars.get(candidate, set())] != [p]:
                raise ValueError('Missing/duplicate/opposite actual native owner in ' + config)
    other = '26.3' if target == '26.2' else '26.2'
    for items in report['modules'].values():
        if not isinstance(items, list) or not items:
            raise ValueError('Empty actual module inventory')
        unique, coordinates = set(), set()
        for item in items:
            p = artifact(item, 'file')
            if not isinstance(item.get('module'), str) or len(item['module'].split(':')) != 3 or any(not x for x in item['module'].split(':')):
                raise ValueError('Malformed actual dependency module')
            if (item['module'], p) in unique:
                raise ValueError('Duplicate actual dependency module artifact')
            unique.add((item['module'], p))
            coordinates.add(item['module'])
            if item['module'].startswith('com.mojang:minecraft:') and item['module'] != 'com.mojang:minecraft:' + target:
                raise ValueError('Opposite-native Minecraft dependency')
            if re.search(r'\+(?:mc[.-]?)?' + re.escape(other) + r'(?:$|[^\d])', item['module']):
                raise ValueError('Opposite-native dependency: ' + item['module'])
            coordinate = item['module'].rsplit(':', 1)
            fixed = {'net.fabricmc:fabric-language-kotlin': PINS['fabricLanguageKotlin'],
                     'net.fabricmc:fabric-loader': '0.19.3' if target == '26.2' else '0.19.5',
                     'net.fabricmc.fabric-api:fabric-api': '0.155.2+26.2' if target == '26.2' else '0.165.0+26.3',
                     'org.jetbrains.kotlin:kotlin-stdlib': PINS['kotlin']}
            if coordinate[0] in fixed and coordinate[1] != fixed[coordinate[0]]:
                raise ValueError('Unpinned native dependency: ' + item['module'])
        if not {name + ':' + version for name, version in fixed.items()} <= coordinates:
            raise ValueError('Incomplete core native dependency module inventory')
    return report


def test_results(lane, started_ns=None, completed_ns=None):
    files = sorted((lane / 'build/test-results/test').glob('TEST-*.xml'))
    if not files:
        raise ValueError('Missing native test results')
    counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    inventory = []
    for file in files:
        regular(file, lane / 'build/test-results/test')
        data = ElementTree.parse(file).getroot()
        if data.tag != 'testsuite' or int(data.attrib.get('tests', 0)) != len(data.findall('testcase')):
            raise ValueError('Malformed/inconsistent native test XML')
        if any(len(data.findall('.//' + tag)) != int(data.attrib.get(key, 0))
               for tag, key in [('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')]):
            raise ValueError('Native test XML hides failures/errors/skips')
        for key in counts:
            value = int(data.attrib.get(key, 0))
            if value < 0:
                raise ValueError('Negative native test XML count')
            counts[key] += value
        modified = file.stat().st_mtime_ns
        if started_ns is not None:
            try:
                stamp = datetime.fromisoformat(data.attrib['timestamp'].replace('Z', '+00:00'))
                if stamp.tzinfo is None:
                    raise ValueError('Native XML timestamp requires timezone')
                stamp_ns = int(stamp.timestamp() * 1_000_000_000)
            except (KeyError, ValueError) as error:
                raise ValueError('Missing/invalid native XML timestamp') from error
            if not (started_ns - 1_000_000_000 <= modified <= completed_ns + 1_000_000_000) or \
                    not (started_ns - 1_000_000_000 <= stamp_ns <= completed_ns + 1_000_000_000):
                raise ValueError('Stale/out-of-run native test XML')
        inventory.append({'path': str(file), 'sha256': digest(file), 'bytes': file.stat().st_size, 'mtimeNs': modified})
    if not counts['tests'] or any(counts[k] for k in ('failures', 'errors', 'skipped')):
        raise ValueError('Failed, empty or skipped native test suite')
    return counts, {'files': inventory, 'sha256': hashlib.sha256(canonical(inventory)).hexdigest()}


def test_counts(lane):
    return test_results(lane)[0]


def build_arguments(root, lane, run, initialize_locks=False):
    arguments = [str(root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), '-p', str(lane),
                 'clean', 'test', 'build', 'effectiveSources', '--console=plain', '--no-daemon', '--max-workers=4',
                 '-PzpbwBuildRunId=' + run]
    if initialize_locks:
        arguments += ['--write-locks', '--write-verification-metadata', 'sha256']
    return arguments


def verify_build_log(root, lane, run, target, source_digest, build):
    if not isinstance(build, dict) or build.get('runId') != run or build.get('target') != target or \
            build.get('sourceDigest') != source_digest or type(build.get('exitCode')) is not int or build['exitCode'] != 0 or build.get('initializeLocks') is not False:
        raise ValueError('Missing/failed/bootstrap/current-run build log receipt')
    if build.get('arguments') != build_arguments(root, lane, run):
        raise ValueError('Build invocation differs from exact clean native command')
    start, end = build.get('startedAtNs'), build.get('completedAtNs')
    if type(start) is not int or type(end) is not int or not 0 < start < end or end > time.time_ns() + 1_000_000_000:
        raise ValueError('Missing/invalid current build interval')
    expected_log = root / 'artifacts/build-runs' / run / f'mc{target}.log'
    if regular(build.get('logPath'), root) != regular(expected_log, root) or digest(expected_log) != build.get('logSha256'):
        raise ValueError('Missing/changed/current-run build log')
    lines = [line for line in expected_log.read_text(encoding='utf-8').splitlines() if line.strip()]
    try:
        begin = json.loads(lines[0].removeprefix('ZPBW_BUILD_INVOCATION '))
        finish = json.loads(lines[-1].removeprefix('ZPBW_BUILD_EXIT '))
    except (ValueError, IndexError) as error:
        raise ValueError('Missing actual build invocation/exit markers') from error
    for marker, label in [(begin, 'ZPBW_BUILD_INVOCATION'), (finish, 'ZPBW_BUILD_EXIT')]:
        if marker.get('kind') != label or marker.get('runId') != run or marker.get('target') != target or marker.get('sourceDigest') != source_digest:
            raise ValueError('Build log current-run/native/source marker mismatch')
    if begin.get('startedAtNs') != start or begin.get('arguments') != build['arguments'] or begin.get('initializeLocks') is not False or \
            finish.get('completedAtNs') != end or type(finish.get('exitCode')) is not int or finish['exitCode'] != 0:
        raise ValueError('Build log interval/exit/invocation mismatch')
    return start, end


def validate_lane_receipt(root, definitions, run, target, version, source_digest, receipt, pins):
    if receipt.get('runId') != run or receipt.get('sourceDigest') != source_digest or receipt.get('success') is not True:
        raise ValueError('Stale, failed or source-mismatched native receipt')
    if receipt.get('target') != target or receipt.get('buildPins') != pins:
        raise ValueError('Wrong native receipt target/build pins')
    lane = lane_root(root, definitions, target)
    started, completed = verify_build_log(root, lane, run, target, source_digest, receipt.get('build'))
    routes = source_routes(root, definitions, target)
    expected_report = lane / 'build/reports/native/effective-sources.json'
    if regular(receipt.get('effectiveSourcesPath'), lane) != regular(expected_report, lane) or \
            digest(expected_report) != receipt.get('effectiveSourcesSha256'):
        raise ValueError('Missing/changed effective source report receipt')
    if not started <= expected_report.stat().st_mtime_ns <= completed:
        raise ValueError('Stale/out-of-run effective source report')
    report = check_effective(root, lane, target, routes, run)
    if report != receipt.get('effectiveSources') or report.get('version') != version:
        raise ValueError('Changed/incomplete effective source report snapshot')
    counts, results = test_results(lane, started, completed)
    if counts != receipt.get('tests') or results != receipt.get('testResults'):
        raise ValueError('Changed/missing native test XML receipt/digest')
    expected_artifact = lane / 'build/libs' / f'zpbw-mc{target}-{version}.jar'
    source = regular(receipt.get('artifactPath'), lane)
    if source != regular(expected_artifact, lane):
        raise ValueError('Native artifact is not the exact current lane output')
    identity = inspect_jar(source, target, version)
    if identity['sha256'] != receipt.get('artifact', {}).get('sha256'):
        raise ValueError('Native artifact changed since its receipt')
    if receipt.get('artifact') != identity:
        raise ValueError('Native artifact receipt identity mismatch')
    if not started <= source.stat().st_mtime_ns <= completed:
        raise ValueError('Native artifact is not the exact current lane output')
    return identity


def collect_pair(root, run, version, source_digest, lane_receipts):
    root = root.resolve()
    if not isinstance(run, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,95}', run):
        raise ValueError('Invalid/traversing build run identity')
    if set(lane_receipts) != set(TARGETS):
        raise ValueError('Both current successful lane receipts required')
    definitions = json.loads(regular(root / 'native-targets.json', root).read_text())
    pins = check_pins(root, definitions)
    if definitions['productVersion'] != version or fingerprint(root)[0] != source_digest:
        raise ValueError('Stale/current source fingerprint or product version mismatch')
    history = root / 'artifacts/native-pairs'
    destination, staged = history / run, history / ('.stage-' + run)
    if history.resolve() != history or not history.resolve().is_relative_to(root):
        raise ValueError('Aliased/escaped pair output root')
    if destination.exists() or staged.exists():
        raise ValueError('Immutable pair/stage identity already exists')
    identities = {target: validate_lane_receipt(root, definitions, run, target, version, source_digest, lane_receipts[target], pins)
                  for target in TARGETS}
    staged.mkdir(parents=True)
    manifest = {'schemaVersion': 1, 'runId': run, 'productVersion': version, 'sourceDigest': source_digest,
                'status': 'BUILT_UNQUALIFIED', 'createdUtc': datetime.now(timezone.utc).isoformat(), 'lanes': {}}
    try:
        for target in TARGETS:
            receipt = lane_receipts[target]
            source = Path(receipt['artifactPath'])
            identity = identities[target]
            output = staged / identity['name']
            shutil.copyfile(source, output)
            if digest(output) != identity['sha256']:
                raise ValueError('Pair artifact changed during copy')
            archive = []
            for path in [Path(receipt['effectiveSourcesPath']), Path(receipt['build']['logPath'])] + [Path(item['path']) for item in receipt['testResults']['files']]:
                archived = staged / 'evidence' / ('mc' + target) / path.name
                archived.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, archived)
                if digest(archived) != digest(path):
                    raise ValueError('Pair evidence changed during copy')
                archive.append({'path': archived.relative_to(staged).as_posix(), 'sha256': digest(archived)})
            write_json(staged / f'receipt-mc{target}.json', {**receipt, 'collectedEvidence': archive})
            manifest['lanes'][target] = identity
        if fingerprint(root)[0] != source_digest:
            raise ValueError('Build inputs changed during pair collection')
        write_json(staged / 'native-pair.json', manifest)
        (staged / 'COMPLETE').write_text(run + '\n')
        staged.rename(destination)
        pointer = root / 'artifacts/current-pair.json'
        temporary = pointer.with_name('.current-pair-' + run + '.tmp')
        write_json(temporary, {'runId': run, 'manifest': str((destination / 'native-pair.json').relative_to(root)),
                               'sha256': digest(destination / 'native-pair.json'), 'status': 'BUILT_UNQUALIFIED'})
        os.replace(temporary, pointer)
        return destination
    except BaseException:
        if staged.exists():
            write_json(staged / 'INCOMPLETE.json', {'runId': run, 'status': 'FAILED'})
        raise


def java_environment():
    env = dict(os.environ)
    choices = [Path(env['JAVA_HOME'])] if env.get('JAVA_HOME') else []
    if os.name == 'nt':
        choices.append(Path('C:/Program Files/Java/jdk-25'))
    for home in choices:
        exe = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if not exe.is_file():
            continue
        info = subprocess.run([str(exe), '-version'], capture_output=True, text=True)
        if re.search(r'version "25(?:\.|\")', info.stderr + info.stdout):
            env['JAVA_HOME'] = str(home)
            env['PATH'] = str(home / 'bin') + os.pathsep + env.get('PATH', '')
            return env
    raise ValueError('Set JAVA_HOME to a Java25 JDK')


def main(args):
    root = ROOT
    definitions = json.loads((root / 'native-targets.json').read_text())
    pins = check_pins(root, definitions, args.initialize_locks)
    before, inputs = fingerprint(root)
    run = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    evidence = root / 'artifacts/build-runs' / run
    evidence.mkdir(parents=True)
    write_json(evidence / 'inputs.json', {'sourceDigest': before, 'files': inputs})
    env = java_environment()
    results, errors = {}, {}
    for target in TARGETS:
        try:
            lane_def = definitions['targets'][target]
            lane = lane_root(root, definitions, target)
            routes = source_routes(root, definitions, target)
            arguments = build_arguments(root, lane, run, args.initialize_locks)
            log_path = evidence / f'mc{target}.log'
            started = time.time_ns()
            invocation = {'kind': 'ZPBW_BUILD_INVOCATION', 'runId': run, 'target': target, 'sourceDigest': before,
                          'startedAtNs': started, 'arguments': arguments, 'initializeLocks': args.initialize_locks}
            with log_path.open('w', encoding='utf-8') as log:
                log.write('ZPBW_BUILD_INVOCATION ' + json.dumps(invocation, sort_keys=True) + '\n'); log.flush()
                code = subprocess.run(arguments, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
                completed = time.time_ns()
                log.seek(0, os.SEEK_END)
                log.write('\nZPBW_BUILD_EXIT ' + json.dumps({'kind': 'ZPBW_BUILD_EXIT', 'runId': run, 'target': target,
                    'sourceDigest': before, 'completedAtNs': completed, 'exitCode': code}, sort_keys=True) + '\n')
            if code:
                raise ValueError(f'Native Gradle build failed ({code}); see {evidence.name}/mc{target}.log')
            report = check_effective(root, lane, target, routes, run)
            artifact_path = lane / 'build/libs' / f'zpbw-mc{target}-{definitions["productVersion"]}.jar'
            identity = inspect_jar(artifact_path, target, definitions['productVersion'])
            counts, xml = test_results(lane, started, completed)
            results[target] = {'runId': run, 'target': target, 'sourceDigest': before, 'success': True, 'buildPins': pins,
                               'artifactPath': str(artifact_path), 'artifact': identity, 'tests': counts, 'testResults': xml,
                               'effectiveSourcesPath': str(lane / 'build/reports/native/effective-sources.json'),
                               'effectiveSources': report, 'effectiveSourcesSha256': digest(lane / 'build/reports/native/effective-sources.json'),
                               'build': {'runId': run, 'target': target, 'sourceDigest': before, 'startedAtNs': started,
                                         'completedAtNs': completed, 'exitCode': code, 'initializeLocks': args.initialize_locks,
                                         'arguments': arguments, 'logPath': str(log_path), 'logSha256': digest(log_path)}}
            write_json(evidence / f'mc{target}.json', results[target])
            print(f'{target}: {results[target]["tests"]["tests"]} tests, {identity["sha256"]}', flush=True)
        except Exception as error:
            errors[target] = str(error)
            write_json(evidence / f'mc{target}-failure.json', {'runId': run, 'target': target, 'status': 'FAILED', 'reason': str(error)})
            print(target + ': ' + str(error), file=sys.stderr, flush=True)
    after, _ = fingerprint(root)
    if args.initialize_locks:
        # A bootstrap is always uncollected, even if dependencies happened to be cached
        # and neither locks nor metadata bytes changed. Trust/review then rerun unchanged.
        write_json(evidence / 'BOOTSTRAP_NOT_COLLECTED.json', {'status': 'BOOTSTRAP_NOT_COLLECTED', 'runId': run,
            'sourceDigestBefore': before, 'sourceDigestAfter': after, 'errors': errors,
            'requiredNextStep': 'Review resolved lock/checksum inputs; rerun without --initialize-locks'})
        return 1 if errors else 0
    if before != after:
        errors['inputs'] = 'Build inputs changed; no pair collected. Inspect changes and rerun unchanged.'
    if errors:
        write_json(evidence / 'FAILED.json', errors)
        return 1
    pair = collect_pair(root, run, definitions['productVersion'], before, results)
    print('BUILT_UNQUALIFIED: ' + str(pair), flush=True)
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initialize-locks', action='store_true', help='Lock/checksum bootstrap only; review resolved metadata and rerun unchanged before collection')
    raise SystemExit(main(parser.parse_args()))
