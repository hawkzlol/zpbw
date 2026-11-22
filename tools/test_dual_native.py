"""Offline negative tests for native source, packaging and pair collection guards.

The tiny archive class markers below are packaging fixtures, not executable Java
classes. Passing these tests establishes no Minecraft/Mixin/runtime qualification.
"""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import os
from datetime import datetime, timezone
from xml.etree import ElementTree
import unittest
from unittest import mock
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location('verify_dual_native', Path(__file__).with_name('verify_dual_native.py'))
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class NativeGuardTests(unittest.TestCase):
    version = '1.0.3'

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='zpbw-native-guards-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.definitions = {**verifier.PINS, 'productVersion': self.version, 'targets': {target: {
            'projectRoot': 'versions/mc' + target.replace('.', '_'),
            'sourceRoot': 'src/native' + target.replace('.', '_'),
            'testRoot': 'src/native' + target.replace('.', '_') + 'Test',
            'loader': '0.19.3' if target=='26.2' else '0.19.5',
            'fabricApi': '0.155.2+26.2' if target=='26.2' else '0.165.0+26.3',
            'requiredNativeFiles': ['kotlin/com/example/NativeIdentity.kt'],
            'replacements': [],
        } for target in verifier.TARGETS}}
        self.write('src/main/kotlin/com/example/Shared.kt', 'package com.example\nclass Shared\n')
        for target in verifier.TARGETS:
            self.write(self.definitions['targets'][target]['sourceRoot'] + '/kotlin/com/example/NativeIdentity.kt',
                       'package com.example\nobject NativeIdentity\n')
        verifier.write_json(self.root/'native-targets.json',self.definitions)
        settings='plugins { id "net.fabricmc.fabric-loom" version "1.17.11"\nid "org.jetbrains.kotlin.jvm" version "2.4.10" }\n'
        for name in ['settings.gradle','versions/mc26_2/settings.gradle','versions/mc26_3/settings.gradle']:
            self.write(name,settings)
        self.write('gradle/zpbw-native.gradle','kotlin { jvmToolchain(25) }\ntasks.withType(JavaCompile).configureEach { options.release = 25 }\n')
        self.write('gradle/wrapper/gradle-wrapper.properties','distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.0-bin.zip\ndistributionSha256Sum='+verifier.WRAPPER_SHA256+'\n')
        self.write('gradle.properties','fabric_kotlin_version=1.13.13+kotlin.2.4.10\n')
        # These checksums identify real temporary fixture bytes, never an official download.
        fixture=self.write('dependencies/verification-fixture.txt','temporary guard fixture bytes')
        for target in verifier.TARGETS:
            lane='versions/mc'+target.replace('.','_')
            self.write(lane+'/gradle.lockfile','temporary guard fixture dependency lock\n')
            self.write(lane+'/gradle/verification-metadata.xml','<verification-metadata><components><component><artifact><sha256 value="'+verifier.digest(fixture)+'"/></artifact></component></components></verification-metadata>')
        for name in ('build.gradle','gradlew','gradlew.bat','LICENSE'):
            self.write(name,'guard fixture '+name+'\n')

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        return path

    def metadata(self, target):
        return {'schemaVersion': 1, 'id': 'zpbw', 'version': self.version, 'environment': 'client', 'license': 'MIT', 'authors': ['hawkslol'],
                'entrypoints': {'client': ['com.hawkslol.zpbw.ZpbwClient']}, 'mixins': ['zpbw.mixins.json'],
                'depends': {'minecraft': target, 'java': '>=25',
                            'fabricloader': '>=0.19.3' if target == '26.2' else '>=0.19.5',
                            'fabric-api': '0.155.2+26.2' if target == '26.2' else '0.165.0+26.3',
                            'fabric-language-kotlin': '1.13.13+kotlin.2.4.10'}}

    def jar(self, target='26.2', *, metadata=None, registry=None, extras=(), omit=(), identity=None):
        path = self.root / 'products' / f'zpbw-mc{target}-{self.version}.jar'
        path.parent.mkdir(parents=True, exist_ok=True)
        registry = registry if registry is not None else {
            'required': True, 'package': 'com.hawkslol.zpbw.mixin', 'client': ['ExampleMixin'],
            'injectors': {'defaultRequire': 1}}
        entries = [('fabric.mod.json', json.dumps(metadata if metadata is not None else self.metadata(target))),
                   ('zpbw.mixins.json', json.dumps(registry)),
                   ('com/hawkslol/zpbw/mixin/ExampleMixin.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x45packaging-fixture'),
                   ('com/hawkslol/zpbw/ZpbwClient.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x45packaging-fixture'),
                   ('com/hawkslol/zpbw/nativebridge/NativeIdentity.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x45' + (identity or target).encode()),
                   ('com/hawkslol/zpbw/api/ExternalPredictionLease.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x45packaging-fixture'),
                   ('dev/zpbw/candidate/api/ExternalPredictionLease.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x45packaging-fixture')]
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'w') as archive:
                for name, data in entries + list(extras):
                    if name not in omit:
                        archive.writestr(name, data)
        return path

    def effective(self, target='26.2',run='fixture-run'):
        routes = verifier.source_routes(self.root, self.definitions, target)
        lane = self.root / ('versions/mc' + target.replace('.', '_'))
        dependency=self.root/'dependencies'/('minecraft-'+target+'.jar')
        with zipfile.ZipFile(dependency,'w') as jar:
            jar.writestr('version.json',json.dumps({'id':target}))
            for name in verifier.NATIVE_CLASSES:jar.writestr(name,b'\xca\xfe\xba\xbe\x00\x00\x00\x45temporary-native-owner-fixture')
        files=[{'path':str(dependency),'sha256':verifier.digest(dependency)}]
        modules=[{'module':'com.mojang:minecraft:'+target,'file':str(dependency),'sha256':verifier.digest(dependency)}]
        for module in ['net.fabricmc:fabric-loader:'+self.definitions['targets'][target]['loader'],
                       'net.fabricmc.fabric-api:fabric-api:'+self.definitions['targets'][target]['fabricApi'],
                       'net.fabricmc:fabric-language-kotlin:'+verifier.PINS['fabricLanguageKotlin'],
                       'org.jetbrains.kotlin:kotlin-stdlib:'+verifier.PINS['kotlin']]:
            path=self.root/'dependencies'/(target+'-'+module.split(':')[1]+'.jar')
            with zipfile.ZipFile(path,'w') as jar:jar.writestr('guard-fixture.txt',module)
            files.append({'path':str(path),'sha256':verifier.digest(path)})
            modules.append({'module':module,'file':str(path),'sha256':verifier.digest(path)})
        report = {'target': target,'version':self.version,'productRoot':str(self.root),'java':'25-fixture', 'buildRunId':run,
                  'sources': {rel: {'path': str(self.root / item['path']), 'sha256': item['sha256'],'owner':item['owner']}
                                              for rel, item in routes.items()},
                  'classpaths': {name:copy.deepcopy(files) for name in verifier.CONFIGURATIONS},
                  'nativeOwners':{name:{'path':str(dependency),'sha256':verifier.digest(dependency)} for name in verifier.NATIVE_CLASSES},
                  'modules': {name:copy.deepcopy(modules) for name in verifier.CONFIGURATIONS}}
        return lane, routes, report

    def check_report(self, lane, routes, report, target='26.2'):
        verifier.write_json(lane / 'build/reports/native/effective-sources.json', report)
        return verifier.check_effective(self.root, lane, target, routes)

    def lane_outputs(self,target,run):
        lane,routes,report=self.effective(target,run)
        path=lane/'build/reports/native/effective-sources.json';verifier.write_json(path,report)
        packaged=self.jar(target)
        artifact=lane/'build/libs'/packaged.name;artifact.parent.mkdir(parents=True,exist_ok=True)
        artifact.write_bytes(packaged.read_bytes())
        timestamp=datetime.now(timezone.utc).isoformat()
        self.write(str((lane/'build/test-results/test/TEST-fixture.xml').relative_to(self.root)),
                   '<testsuite tests="3" failures="0" errors="0" skipped="0" timestamp="'+timestamp+'"><testcase name="a"/><testcase name="b"/><testcase name="c"/></testsuite>')
        return lane,path,report,artifact

    def pair_receipts(self, run='new-run', source=None):
        source=source or verifier.fingerprint(self.root)[0]
        pins=verifier.check_pins(self.root,self.definitions)
        receipts = {}
        for target in verifier.TARGETS:
            started=time.time_ns()
            lane,path,report,artifact=self.lane_outputs(target,run)
            completed=time.time_ns()
            arguments=verifier.build_arguments(self.root,lane,run)
            begin={'kind':'ZPBW_BUILD_INVOCATION','runId':run,'target':target,'sourceDigest':source,
                   'startedAtNs':started,'arguments':arguments,'initializeLocks':False}
            finish={'kind':'ZPBW_BUILD_EXIT','runId':run,'target':target,'sourceDigest':source,'completedAtNs':completed,'exitCode':0}
            log=self.write(f'artifacts/build-runs/{run}/mc{target}.log','ZPBW_BUILD_INVOCATION '+json.dumps(begin)+'\nfixture Gradle output (not a real build)\nZPBW_BUILD_EXIT '+json.dumps(finish)+'\n')
            counts,xml=verifier.test_results(lane,started,completed)
            receipts[target] = {'runId': run, 'target': target, 'sourceDigest': source, 'success': True,'buildPins':pins,
                                'artifactPath': str(artifact), 'artifact': verifier.inspect_jar(artifact, target, self.version),
                                'tests': counts,'testResults':xml,'effectiveSourcesPath':str(path),'effectiveSources':report,
                                'effectiveSourcesSha256':verifier.digest(path),
                                'build':{'runId':run,'target':target,'sourceDigest':source,'arguments':arguments,
                                         'startedAtNs':started,'completedAtNs':completed,'exitCode':0,'initializeLocks':False,
                                         'logPath':str(log),'logSha256':verifier.digest(log)}}
        return receipts

    def collect(self,receipts,run='new-run'):
        return verifier.collect_pair(self.root,run,self.version,verifier.fingerprint(self.root)[0],receipts)

    def old_pointer(self):
        pointer = self.write('artifacts/current-pair.json', '{"runId":"prior-run","keep":true}\n')
        self.write('artifacts/native-pairs/unknown-owner.txt', 'preserve unknown data')
        self.write('artifacts/native-pairs/prior-run/COMPLETE','prior-run\n')
        self.write('artifacts/native-pairs/prior-run/frozen-binary.jar','previous immutable pair fixture bytes\n')
        return pointer, pointer.read_bytes()

    def assert_old_pointer(self, pointer, original):
        self.assertEqual(original, pointer.read_bytes())
        self.assertEqual('preserve unknown data', (self.root / 'artifacts/native-pairs/unknown-owner.txt').read_text())
        self.assertEqual('prior-run\n',(self.root/'artifacts/native-pairs/prior-run/COMPLETE').read_text())
        self.assertEqual('previous immutable pair fixture bytes\n',(self.root/'artifacts/native-pairs/prior-run/frozen-binary.jar').read_text())

    def test_wrong_java_bytecode_is_rejected(self):
        artifact = self.jar(extras=[('com/hawkslol/zpbw/Legacy.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x41fixture')])
        with self.assertRaisesRegex(ValueError, 'Non-Java25'):
            verifier.inspect_jar(artifact, '26.2', self.version)

    def test_source_routes_select_exact_shared_and_native_inputs(self):
        for target in verifier.TARGETS:
            routes = verifier.source_routes(self.root, self.definitions, target)
            self.assertEqual({'shared', target}, {item['owner'] for item in routes.values()})
            for item in routes.values():
                self.assertEqual(verifier.digest(self.root / item['path']), item['sha256'])

    def test_missing_required_native_file_is_rejected(self):
        (self.root / self.definitions['targets']['26.2']['sourceRoot'] / 'kotlin/com/example/NativeIdentity.kt').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing required native source'):
            verifier.source_routes(self.root, self.definitions, '26.2')

    def test_unlisted_native_implementation_is_rejected(self):
        self.write('src/native26_2/kotlin/com/example/Rogue.kt', 'package com.example\nclass Rogue\n')
        with self.assertRaisesRegex(ValueError, 'Unlisted native implementation'):
            verifier.source_routes(self.root, self.definitions, '26.2')

    def test_unapproved_duplicate_source_or_resource_route_is_rejected(self):
        for relative, text in [('kotlin/com/example/Shared.kt', 'package com.example\nclass Shared\n'),
                               ('resources/example.json', '{}')]:
            with self.subTest(relative=relative):
                self.write('src/main/' + relative, text)
                native = self.write('src/native26_2/' + relative, text)
                self.definitions['targets']['26.2']['requiredNativeFiles'].append(relative)
                with self.assertRaisesRegex(ValueError, 'Unapproved native replacement'):
                    verifier.source_routes(self.root, self.definitions, '26.2')
                native.unlink()
                self.definitions['targets']['26.2']['requiredNativeFiles'].remove(relative)

    def test_allowed_replacement_selects_native_owner_and_hash(self):
        rel = 'kotlin/com/example/Shared.kt'
        native = self.write('src/native26_2/' + rel, 'package com.example\nclass Shared // selected native\n')
        self.definitions['targets']['26.2']['requiredNativeFiles'].append(rel)
        self.definitions['targets']['26.2']['replacements'].append(rel)
        routes = verifier.source_routes(self.root, self.definitions, '26.2')
        self.assertEqual('26.2', routes[rel]['owner'])
        self.assertEqual(verifier.digest(native), routes[rel]['sha256'])

    def test_manifest_cannot_claim_a_replacement_that_does_not_exist(self):
        self.definitions['targets']['26.2']['replacements'].append('kotlin/com/example/Shared.kt')
        with self.assertRaisesRegex(ValueError, 'replacement manifest'):
            verifier.source_routes(self.root, self.definitions, '26.2')

    def test_duplicate_fqcn_across_different_java_and_kotlin_paths_is_rejected(self):
        self.write('src/main/java/different/Name.java', 'package com.example;\npublic class Shared {}\n')
        with self.assertRaisesRegex(ValueError, 'Duplicate FQCN: com.example.Shared'):
            verifier.source_routes(self.root, self.definitions, '26.2')

    def test_jar_accepts_only_exact_product_native_identity_for_both_lanes(self):
        for target in verifier.TARGETS:
            path = self.jar(target)
            identity = verifier.inspect_jar(path, target, self.version)
            self.assertEqual(path.name, identity['name'])
            self.assertEqual(target, identity['metadata']['depends']['minecraft'])
            self.assertEqual(verifier.digest(path), identity['sha256'])
            self.assertEqual(5, identity['classCount'])

    def test_wrong_jar_filename_product_version_or_native_metadata_is_rejected(self):
        for field, value in [('id', 'other'), ('version', '1.0.2'), ('minecraft', '26.3')]:
            with self.subTest(field=field):
                metadata = self.metadata('26.2')
                if field == 'minecraft':
                    metadata['depends'][field] = value
                else:
                    metadata[field] = value
                with self.assertRaisesRegex(ValueError, 'Wrong product/native metadata'):
                    verifier.inspect_jar(self.jar(metadata=metadata), '26.2', self.version)
        path = self.jar()
        renamed = path.with_name('zpbw-1.0.3.jar')
        path.rename(renamed)
        with self.assertRaises(ValueError):
            verifier.inspect_jar(renamed, '26.2', self.version)

    def test_duplicate_archive_entries_including_metadata_are_rejected(self):
        for name, data in [('fabric.mod.json', '{}'), ('com/hawkslol/zpbw/ZpbwClient.class', b'duplicate')]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Duplicate archive'):
                verifier.inspect_jar(self.jar(extras=[(name, data)]), '26.2', self.version)

    def test_wrong_entrypoint_or_mixin_registration_is_rejected(self):
        for key, value in [('entrypoints', {'client': ['wrong.Entry']}), ('mixins', ['wrong.mixins.json'])]:
            metadata = self.metadata('26.2'); metadata[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'entrypoint/Mixin'):
                verifier.inspect_jar(self.jar(metadata=metadata), '26.2', self.version)

    def test_missing_entrypoint_class_is_rejected(self):
        with self.assertRaises(ValueError):
            verifier.inspect_jar(self.jar(omit=['com/hawkslol/zpbw/ZpbwClient.class']), '26.2', self.version)

    def test_weak_or_unpackaged_registered_mixin_is_rejected(self):
        for registry in [{'required': False, 'package': 'com.example', 'client': [], 'injectors': {'defaultRequire': 1}},
                         {'required': True, 'package': 'com.example', 'client': [], 'injectors': {'defaultRequire': 0}}]:
            with self.assertRaisesRegex(ValueError, 'Weakened native Mixin'):
                verifier.inspect_jar(self.jar(registry=registry), '26.2', self.version)
        with self.assertRaisesRegex(ValueError, 'Unpackaged registered Mixin'):
            verifier.inspect_jar(self.jar(omit=['com/hawkslol/zpbw/mixin/ExampleMixin.class']), '26.2', self.version)

    def test_both_public_lease_api_names_are_required(self):
        for name in ['com/hawkslol/zpbw/api/ExternalPredictionLease.class', 'dev/zpbw/candidate/api/ExternalPredictionLease.class']:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Missing public prediction lease API'):
                verifier.inspect_jar(self.jar(omit=[name]), '26.2', self.version)

    def test_private_and_bundled_native_classes_are_rejected(self):
        for name in ['dev/zpew/Private.class', 'dev/zpbw/control/Driver.class', 'com/hawkslol/blackwell/Client.class',
                     'dev/zpbw/traffic/TrafficMatrixProbe.class', 'DEV/ZPBW/TRAFFIC/TrafficMatrixProbe.class',
                     'org/lwjgl/Injected.class', 'net/minecraft/Injected.class', 'com/example/Harness.class',
                     'data/accounts.json', '.env.local', 'evidence/run-receipt.json']:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'leaked into product'):
                verifier.inspect_jar(self.jar(extras=[(name, b'private')]), '26.2', self.version)

    def test_native_class_marker_cannot_contain_opposite_or_missing_identity(self):
        for identity in ['26.3', '26.2 26.3', 'unknown']:
            with self.subTest(identity=identity), self.assertRaisesRegex(ValueError, 'native identity mismatch'):
                verifier.inspect_jar(self.jar(identity=identity), '26.2', self.version)

    def test_client_environment_license_and_pinned_dependencies_are_required(self):
        for field, value in [('environment', '*'), ('license', 'Other'), ('java', '>=17'),
                             ('fabricloader', '*'), ('fabric-api', '*'), ('fabric-language-kotlin', '*')]:
            with self.subTest(field=field):
                metadata = self.metadata('26.2')
                (metadata['depends'] if field in metadata['depends'] else metadata)[field] = value
                with self.assertRaises(ValueError):
                    verifier.inspect_jar(self.jar(metadata=metadata), '26.2', self.version)

    def test_effective_report_matches_selected_source_owners_and_hashes(self):
        lane, routes, report = self.effective()
        self.assertEqual(report, self.check_report(lane, routes, report))
        for mutation in ['target', 'missing', 'path', 'hash']:
            changed = copy.deepcopy(report)
            key = next(iter(routes))
            if mutation == 'target': changed['target'] = '26.3'
            if mutation == 'missing': del changed['sources'][key]
            if mutation == 'path': changed['sources'][key]['path'] = str(self.root / 'wrong.kt')
            if mutation == 'hash': changed['sources'][key]['sha256'] = 'stale'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.check_report(lane, routes, changed)

    def test_effective_report_rejects_changed_or_opposite_lane_classpath(self):
        lane, routes, report = self.effective()
        changed = copy.deepcopy(report); changed['classpaths']['compileClasspath'][0]['sha256'] = 'stale'
        with self.assertRaises(ValueError): self.check_report(lane, routes, changed)
        opposite = self.write('versions/mc26_3/build/libs/opposite.jar', 'wrong native output')
        changed['classpaths']['compileClasspath'][0] = {'path': str(opposite), 'sha256': verifier.digest(opposite)}
        with self.assertRaises(ValueError): self.check_report(lane, routes, changed)

    def test_effective_report_rejects_opposite_native_module_coordinates(self):
        lane, routes, report = self.effective()
        for module in ['net.fabricmc.fabric-api:fabric-api:0.165.0+26.3', 'com.mojang:minecraft:26.3']:
            changed = copy.deepcopy(report); changed['modules']['compileClasspath'][0]['module'] = module
            with self.subTest(module=module), self.assertRaises(ValueError):
                self.check_report(lane, routes, changed)

    def test_fingerprint_changes_for_actual_source_wrapper_and_pin_bytes(self):
        for name in ['native-targets.json', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'LICENSE']:
            self.write(name, name + '\n')
        source = self.write('src/main/resources/example.txt', 'line\n')
        source.write_bytes(b'line\n')
        before, inputs = verifier.fingerprint(self.root)
        self.assertTrue(any(path == 'src/main/resources/example.txt' for path, _ in inputs))
        source.write_bytes(b'line\r\n')
        after, _ = verifier.fingerprint(self.root)
        self.assertNotEqual(before, after, 'Actual compiled bytes must not be normalized away')
        for name in ['gradlew.bat', 'versions/mc26_2/gradle.lockfile', 'tools/verify.py']:
            previous, _ = verifier.fingerprint(self.root)
            self.write(name, 'new protected input')
            self.assertNotEqual(previous, verifier.fingerprint(self.root)[0], name)
        previous, _ = verifier.fingerprint(self.root)
        self.write('versions/mc26_2/build/temporary.txt', 'excluded output')
        self.write('tools/__pycache__/ignored.pyc', 'excluded cache')
        self.assertEqual(previous, verifier.fingerprint(self.root)[0])

    def test_complete_pair_has_exact_artifacts_metadata_and_matching_pointer(self):
        receipts = self.pair_receipts()
        destination = self.collect(receipts)
        manifest = json.loads((destination / 'native-pair.json').read_text())
        self.assertEqual('BUILT_UNQUALIFIED', manifest['status'])
        self.assertEqual(set(verifier.TARGETS), set(manifest['lanes']))
        self.assertEqual('new-run\n', (destination / 'COMPLETE').read_text())
        pointer = json.loads((self.root / 'artifacts/current-pair.json').read_text())
        self.assertEqual(verifier.digest(destination / 'native-pair.json'), pointer['sha256'])
        for target in verifier.TARGETS:
            output = destination / f'zpbw-mc{target}-{self.version}.jar'
            self.assertEqual(receipts[target]['artifact']['sha256'], verifier.digest(output))
            self.assertEqual(target, manifest['lanes'][target]['metadata']['depends']['minecraft'])
        with self.assertRaisesRegex(ValueError, 'Immutable'):
            self.collect(receipts)

    def test_missing_stale_failed_and_source_mismatched_receipts_preserve_previous_pair(self):
        pointer, original = self.old_pointer()
        for case in ['missing', 'runId', 'sourceDigest', 'success']:
            run = 'case-' + case
            receipts = self.pair_receipts(run=run)
            if case == 'missing': del receipts['26.3']
            else: receipts['26.3'][case] = False if case == 'success' else 'stale'
            with self.subTest(case=case), self.assertRaises(ValueError):
                self.collect(receipts,run)
            self.assert_old_pointer(pointer, original)

    def test_receipt_native_identity_and_test_failures_must_match_collection(self):
        pointer, original = self.old_pointer()
        for case in ['target', 'artifact-name', 'failed-tests', 'skipped-tests']:
            with self.subTest(case=case):
                pointer.write_bytes(original)
                run = 'case-' + case
                receipts = self.pair_receipts(run=run)
                if case == 'target': receipts['26.3']['target'] = '26.2'
                if case == 'artifact-name': receipts['26.3']['artifact']['name'] = 'wrong-native.jar'
                if case == 'failed-tests': receipts['26.3']['tests']['failures'] = 1
                if case == 'skipped-tests': receipts['26.3']['tests']['skipped'] = 1
                with self.assertRaises(ValueError):
                    self.collect(receipts,run)
                self.assert_old_pointer(pointer, original)

    def test_same_version_artifact_changed_after_receipt_is_rejected(self):
        receipts = self.pair_receipts()
        pointer, original = self.old_pointer()
        with zipfile.ZipFile(receipts['26.3']['artifactPath'], 'a') as archive:
            archive.writestr('freshness-proof.txt', 'changed same version bytes')
        with self.assertRaisesRegex(ValueError, 'changed since its receipt'):
            self.collect(receipts)
        self.assert_old_pointer(pointer, original)

    def test_copy_failure_or_changed_copy_preserves_previous_pointer_and_unknown_files(self):
        receipts = self.pair_receipts()
        pointer, original = self.old_pointer()
        real_copy = verifier.shutil.copyfile
        def failing_copy(source, output):
            if 'mc26.3' in Path(source).name: raise OSError('controlled second copy failure')
            return real_copy(source, output)
        with mock.patch.object(verifier.shutil, 'copyfile', side_effect=failing_copy), self.assertRaises(OSError):
            self.collect(receipts)
        self.assert_old_pointer(pointer, original)
        stage = self.root / 'artifacts/native-pairs/.stage-new-run'
        self.assertTrue((stage / 'INCOMPLETE.json').is_file()); self.assertFalse((stage / 'COMPLETE').exists())
        changed_receipts = self.pair_receipts(run='changed-copy')
        def changed_copy(source, output): Path(output).write_bytes(b'changed during copy')
        with mock.patch.object(verifier.shutil, 'copyfile', side_effect=changed_copy), self.assertRaisesRegex(ValueError, 'changed during copy'):
            self.collect(changed_receipts,'changed-copy')
        self.assert_old_pointer(pointer, original)

    def test_pair_rename_and_pointer_replace_failures_preserve_previous_pointer(self):
        pointer, original = self.old_pointer()
        receipts = self.pair_receipts()
        with mock.patch.object(Path, 'rename', side_effect=OSError('controlled pair rename failure')), self.assertRaises(OSError):
            self.collect(receipts)
        self.assert_old_pointer(pointer, original)
        receipts = self.pair_receipts(run='pointer-failure')
        with mock.patch.object(verifier.os, 'replace', side_effect=OSError('controlled pointer replacement failure')), self.assertRaises(OSError):
            self.collect(receipts,'pointer-failure')
        self.assert_old_pointer(pointer, original)

    def test_exact_build_pins_and_existing_wrapper_checksum_are_required(self):
        verifier.check_pins(self.root,self.definitions)
        for key,value in [('java',17),('kotlin','2.4.0'),('loom','1.17.10'),('gradle','9.5.0'),('fabricLanguageKotlin','old')]:
            changed=copy.deepcopy(self.definitions);changed[key]=value
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'build pins'):
                verifier.check_pins(self.root,changed)
        path=self.root/'gradle/wrapper/gradle-wrapper.properties';before=path.read_text()
        for content in [before.split('distributionSha256Sum')[0],before.replace(verifier.WRAPPER_SHA256,'0'*64),before.replace('gradle-9.6.0','gradle-9.5.0')]:
            path.write_text(content)
            with self.subTest(content=content),self.assertRaisesRegex(ValueError,'wrapper'):
                verifier.check_pins(self.root,self.definitions)
        path.write_text(before)

    def test_compiler_plugin_and_flk_property_drift_fail_closed(self):
        mutations=[('settings.gradle','2.4.10','2.3.0'),('versions/mc26_3/settings.gradle','1.17.11','1.17.10'),
                   ('gradle/zpbw-native.gradle','jvmToolchain(25)','jvmToolchain(17)'),
                   ('gradle/zpbw-native.gradle','release = 25','release = 17'),
                   ('gradle.properties','1.13.13+kotlin.2.4.10','1.13.0+kotlin.2.3.0')]
        for name,old,new in mutations:
            path=self.root/name;before=path.read_text();path.write_text(before.replace(old,new))
            with self.subTest(name=name,old=old),self.assertRaises(ValueError):verifier.check_pins(self.root,self.definitions)
            path.write_text(before)

    def test_dependency_metadata_requires_real_sha256_entries(self):
        path=self.root/'versions/mc26_3/gradle/verification-metadata.xml';before=path.read_bytes()
        path.unlink()
        with self.assertRaises(ValueError):verifier.check_pins(self.root,self.definitions)
        verifier.check_pins(self.root,self.definitions,initialize_locks=True)
        for content in ['<verification-metadata/>','<verification-metadata><sha256 value="missing"/></verification-metadata>']:
            path.write_text(content)
            with self.assertRaisesRegex(ValueError,'verification SHA256'):verifier.check_pins(self.root,self.definitions)
        path.write_bytes(before)

    def test_bootstrap_flags_request_checksums_and_never_collect_unchanged_inputs(self):
        from types import SimpleNamespace
        calls=[]
        def fake_gradle(arguments,**kwargs):
            self.assertIn('--write-locks',arguments)
            index=arguments.index('--write-verification-metadata');self.assertEqual(arguments[index+1],'sha256')
            run=next(arg.split('=',1)[1] for arg in arguments if arg.startswith('-PzpbwBuildRunId='))
            lane=Path(arguments[arguments.index('-p')+1]);target='26.2' if lane.name=='mc26_2' else '26.3'
            self.lane_outputs(target,run);calls.append(target)
            kwargs['stdout'].write('Mocked Gradle fixture only; no subprocess was launched\n');kwargs['stdout'].flush()
            return SimpleNamespace(returncode=0)
        with mock.patch.object(verifier,'ROOT',self.root),mock.patch.object(verifier,'java_environment',return_value={}), \
             mock.patch.object(verifier.subprocess,'run',side_effect=fake_gradle),mock.patch.object(verifier,'collect_pair') as collect:
            self.assertEqual(verifier.main(SimpleNamespace(initialize_locks=True)),0)
            collect.assert_not_called()
        self.assertEqual(calls,list(verifier.TARGETS))
        receipts=list((self.root/'artifacts/build-runs').glob('*/BOOTSTRAP_NOT_COLLECTED.json'))
        self.assertEqual(len(receipts),1)
        state=json.loads(receipts[0].read_text());self.assertEqual(state['sourceDigestBefore'],state['sourceDigestAfter'])

    def test_every_classpath_and_module_configuration_must_be_present_nonempty(self):
        lane,routes,report=self.effective()
        for group in ('classpaths','modules'):
            for config in verifier.CONFIGURATIONS:
                for mutation in ('missing','empty'):
                    changed=copy.deepcopy(report)
                    if mutation=='missing':del changed[group][config]
                    else:changed[group][config]=[]
                    with self.subTest(group=group,config=config,mutation=mutation),self.assertRaises(ValueError):
                        self.check_report(lane,routes,changed)

    def test_test_compile_only_opposite_module_cannot_escape(self):
        lane,routes,report=self.effective()
        report['modules']['testCompileClasspath'][0]['module']='com.mojang:minecraft:26.3'
        with self.assertRaisesRegex(ValueError,'Opposite-native Minecraft'):
            self.check_report(lane,routes,report)

    def test_native_owners_require_exact_five_and_actual_jar_hash_ownership(self):
        lane,routes,report=self.effective()
        for change in ('missing','extra','hash','path'):
            changed=copy.deepcopy(report);name=verifier.NATIVE_CLASSES[0]
            if change=='missing':del changed['nativeOwners'][name]
            if change=='extra':changed['nativeOwners']['wrong/Class.class']=changed['nativeOwners'][name]
            if change=='hash':changed['nativeOwners'][name]['sha256']='0'*64
            if change=='path':changed['nativeOwners'][name]=copy.deepcopy(changed['classpaths']['compileClasspath'][1])
            with self.subTest(change=change),self.assertRaises(ValueError):self.check_report(lane,routes,changed)

    def test_native_owner_version_comes_from_actual_jar_not_report_target(self):
        lane,routes,report=self.effective()
        _,_,opposite=self.effective('26.3')
        replacement=opposite['classpaths']['compileClasspath'][0]
        for entries in report['classpaths'].values():entries[0]=copy.deepcopy(replacement)
        report['nativeOwners']={name:copy.deepcopy(replacement) for name in verifier.NATIVE_CLASSES}
        with self.assertRaisesRegex(ValueError,'owner version ID mismatch'):
            self.check_report(lane,routes,report)

    def test_report_source_owner_and_java_identity_cannot_be_empty(self):
        lane,routes,report=self.effective()
        for change in ('owner','java','root'):
            changed=copy.deepcopy(report)
            if change=='owner':changed['sources'][next(iter(routes))]['owner']=''
            if change=='java':changed['java']='17'
            if change=='root':changed['productRoot']=''
            with self.subTest(change=change),self.assertRaises(ValueError):self.check_report(lane,routes,changed)

    def test_missing_report_log_and_test_xml_receipts_preserve_previous_pair(self):
        pointer,original=self.old_pointer()
        for field in ('effectiveSourcesPath','effectiveSourcesSha256','effectiveSources','testResults','build'):
            run='missing-'+field;receipts=self.pair_receipts(run=run);del receipts['26.3'][field]
            with self.subTest(field=field),self.assertRaises(ValueError):self.collect(receipts,run)
            self.assert_old_pointer(pointer,original)

    def test_changed_report_is_revalidated_even_if_receipt_hash_is_rewritten(self):
        receipts=self.pair_receipts();receipt=receipts['26.3'];path=Path(receipt['effectiveSourcesPath'])
        report=json.loads(path.read_text());report['buildRunId']='prior-run';verifier.write_json(path,report)
        receipt['effectiveSources']=report;receipt['effectiveSourcesSha256']=verifier.digest(path)
        # Keep the test focused on semantic run identity, not later modification time.
        os.utime(path,ns=(receipt['build']['completedAtNs'],receipt['build']['completedAtNs']))
        with self.assertRaisesRegex(ValueError,'current run mismatch'):self.collect(receipts)

    def test_changed_or_missing_test_xml_and_digest_cannot_be_replaced_by_test_count(self):
        for mutation in ('delete','change','digest'):
            run='xml-'+mutation;receipts=self.pair_receipts(run=run);receipt=receipts['26.3']
            path=Path(receipt['testResults']['files'][0]['path'])
            if mutation=='delete':path.unlink()
            if mutation=='change':path.write_text(path.read_text().replace('name="a"','name="changed"'))
            if mutation=='digest':receipt['testResults']['sha256']='0'*64
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):self.collect(receipts,run)

    def test_stale_xml_is_rejected_even_with_freshly_rewritten_receipt_hash(self):
        receipts=self.pair_receipts();receipt=receipts['26.3'];path=Path(receipt['testResults']['files'][0]['path'])
        xml=path.read_text();stamp=ElementTree.fromstring(xml).attrib['timestamp']
        path.write_text(xml.replace(stamp,'2000-01-01T00:00:00+00:00'))
        receipt['tests'],receipt['testResults']=verifier.test_results(path.parents[3])
        with self.assertRaisesRegex(ValueError,'Stale/out-of-run native test XML'):self.collect(receipts)

    def test_build_log_exit_current_run_and_file_hash_are_required(self):
        for change in ('hash','exit','boolean-exit','run','path','bootstrap'):
            run='log-'+change;receipts=self.pair_receipts(run=run);build=receipts['26.3']['build']
            if change=='hash':build['logSha256']='0'*64
            if change=='exit':build['exitCode']=1
            if change=='boolean-exit':build['exitCode']=False
            if change=='run':build['runId']='old-run'
            if change=='path':build['logPath']=receipts['26.2']['build']['logPath']
            if change=='bootstrap':build['initializeLocks']=True
            with self.subTest(change=change),self.assertRaises(ValueError):self.collect(receipts,run)

    def test_exact_lane_output_required_not_any_same_named_artifact(self):
        receipts=self.pair_receipts()
        receipts['26.2']['artifactPath']=str(self.root/'products'/receipts['26.2']['artifact']['name'])
        with self.assertRaises(ValueError):self.collect(receipts)

    def test_case_run_and_manifest_paths_cannot_traverse_owner(self):
        receipts=self.pair_receipts();pointer,original=self.old_pointer()
        for run in ('../escape','case/../../escape','case\\..\\escape','C:/escape'):
            with self.subTest(run=run),self.assertRaisesRegex(ValueError,'traversing build run'):
                self.collect(receipts,run)
            self.assert_old_pointer(pointer,original)
        for field,value in [('projectRoot','versions/mc26_2/../mc26_3'),('sourceRoot','src/native26_2/../../foreign')]:
            definitions=copy.deepcopy(self.definitions);definitions['targets']['26.2'][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):verifier.check_pins(self.root,definitions)

    def test_archive_traversal_paths_are_rejected_before_packaging(self):
        for name in ('../dev/zpbw/traffic/Private.class','/escaped.class','C:/escaped.class','other\\Private.class'):
            with self.subTest(name=name),self.assertRaises(ValueError):verifier.inspect_jar(self.jar(extras=[(name,b'fixture')]),'26.2',self.version)

    def test_immutable_pair_archives_mutable_report_and_xml_evidence(self):
        receipts=self.pair_receipts();destination=self.collect(receipts)
        for target in verifier.TARGETS:
            receipt=json.loads((destination/f'receipt-mc{target}.json').read_text())
            self.assertEqual(len(receipt['collectedEvidence']),3)
            for ref in receipt['collectedEvidence']:
                self.assertEqual(verifier.digest(destination/ref['path']),ref['sha256'])
            Path(receipts[target]['effectiveSourcesPath']).write_text('replaced by later build')
            Path(receipts[target]['testResults']['files'][0]['path']).write_text('replaced by later build')
            for ref in receipt['collectedEvidence']:
                self.assertEqual(verifier.digest(destination/ref['path']),ref['sha256'])


if __name__ == '__main__':
    unittest.main()
