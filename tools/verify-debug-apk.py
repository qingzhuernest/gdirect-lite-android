#!/usr/bin/env python3
"""Verify the actual debug APK before publishing its uniquely named artifact."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--aapt2', required=True)
parser.add_argument('--apksigner', required=True)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--revision', required=True)
parser.add_argument('--run', default='local')
parser.add_argument('--signing-mode', choices=('pinned-test', 'temporary-debug'), default='temporary-debug')
parser.add_argument('--output', type=Path, required=True)
role = parser.add_mutually_exclusive_group()
role.add_argument('--probe', action='store_true', help='Verify the separate offline installed-package inspector')
role.add_argument('--preview', action='store_true', help='Verify the coexisting app and its pinned test certificate')
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', args.revision) or not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', args.run):
    raise SystemExit('Invalid build identity')

def command(*parts):
    return subprocess.run(parts, check=True, capture_output=True, text=True).stdout

badging = command(args.aapt2, 'dump', 'badging', str(args.apk))
package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
expected_package = 'dev.gdirect.lite.probe' if args.probe else ('dev.gdirect.lite.preview' if args.preview else 'dev.gdirect.lite')
expected_version = '0.2.1-diagnostic-probe' if args.probe else '1.0.3-preview'
expected_code = '3' if args.probe else '25'
if not package or package.groups() != (expected_package, expected_code, expected_version):
    raise SystemExit('APK package or diagnostic version does not match the expected build')
if not args.probe and "application-label:'RouteShelf'" not in badging:
    raise SystemExit('The actual application label is not RouteShelf')
if args.probe and re.search(r"uses-permission(?:-sdk-\d+)?: name='android.permission.INTERNET'", badging):
    raise SystemExit('Offline package inspector must not request INTERNET permission')
if args.probe and 'probe_title' not in command(args.aapt2, 'dump', 'resources', str(args.apk)):
    raise SystemExit('Offline package inspector resources are missing')
resources = command(args.aapt2, 'dump', 'resources', str(args.apk))
if '已取消授权；未连接 Google Drive' in resources:
    raise SystemExit('Old cancellation-only error message is still packaged in the APK')
required_resources = ('probe_title', 'probe_explanation') if args.probe else (
    'build_identity', 'diagnostics_title', 'drive_auth_no_result', 'drive_auth_status',
    'drive_nav_home', 'drive_create', 'ic_launcher_art', 'ic_launcher_monochrome', 'builtin_google_candidates', 'settings_title', 'settings_content', 'connection_logs', 'drive_restoring', 'drive_restore_required', 'connection_status_title', 'connection_indicator', 'connection_headline', 'ui_transfer_detail_title', 'public_link_field', 'transfer_detail_root', 'transfer_open', 'public_transfer_link', 'drive_secondary_container', 'drive_surface_container_high', 'Widget.GDirect.Navigation')
for resource in required_resources:
    if resource not in resources:
        raise SystemExit('Missing diagnostic resource: ' + resource)
with zipfile.ZipFile(args.apk) as apk:
    root = Path(__file__).resolve().parents[1]
    notices = apk.read('assets/third-party-notices.txt')
    if (root / 'LICENSE').read_bytes() not in notices:
        raise SystemExit('The APK is missing the project license')
    if not args.probe and (root / 'LICENSES/Google-Play-Services-exception.txt').read_bytes() not in notices:
        raise SystemExit('The APK is missing the Android linking permission')
    if not args.probe:
        bootstrap_source = Path(__file__).resolve().parents[1] / 'app/src/main/resources/google-bootstrap.json'
        if apk.read('google-bootstrap.json') != bootstrap_source.read_bytes():
            raise SystemExit('Packaged Google bootstrap candidates do not match the reviewed source')
        trust_config = json.loads((Path(__file__).resolve().parents[1] / 'config/google-trust-roots.json').read_text())
        roots = apk.read(trust_config['bundle'])
        if hashlib.sha256(roots).hexdigest() != trust_config['sha256'] or roots.count(b'-----BEGIN CERTIFICATE-----') != trust_config['roots']:
            raise SystemExit('Packaged Google root certificate bundle does not match the reviewed roots')
        if 'gts-roots.LICENSE' not in apk.namelist():
            raise SystemExit('Google root certificate source/license notice is missing')
    dex = [apk.read(name) for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
    required_code = [args.revision]
    if not args.probe:
        required_code += ['Ldev/gdirect/lite/SettingsActivity;', 'Ldev/gdirect/lite/SettingsActivity$Preferences;',
                          'Landroidx/preference/PreferenceFragmentCompat;', 'Ldev/gdirect/lite/network/RouteProbeQueue;',
                          'Ldev/gdirect/lite/network/NetworkPathTracker;', 'Ldev/gdirect/lite/auth/RememberedAuthorization;', 'Ldev/gdirect/lite/ConnectionActivity;', 'Ldev/gdirect/lite/TransferActivity;', 'Ldev/gdirect/lite/ui/TransferText;', 'Ldev/gdirect/lite/ui/AppScreens;', 'Ldev/gdirect/lite/ui/FileMenu;',
                          'Ldev/gdirect/lite/network/ConnectionHealth;', 'Ldev/gdirect/lite/ui/ConnectionText;', 'Ldev/gdirect/lite/ui/NavigationSelection;', 'Ldev/gdirect/lite/network/ConnectionCheck;', 'Ldev/gdirect/lite/drive/BrowseReconnect;',
                          'Ldev/gdirect/lite/drive/SelectionRetention;', 'Ldev/gdirect/lite/DriveActivity$SelectionModel;', 'Ldev/gdirect/lite/drive/DriveSession$RefreshRequired;',
                          'Ldev/gdirect/lite/LogActivity;', 'Ldev/gdirect/lite/diagnostics/DiagnosticLog;', 'Ldev/gdirect/lite/diagnostics/DiagnosticLogStore;']
    if args.probe:
        required_code.append('Ldev/gdirect/lite/probe/PackageProbeActivity;')
        for forbidden in ('Ldev/gdirect/lite/DriveActivity;', 'Ldev/gdirect/lite/DownloadService;', 'Lcom/google/android/gms/auth/api/identity/AuthorizationClient;'):
            if any(forbidden.encode('ascii') in part for part in dex):
                raise SystemExit('Offline inspector contains login/download code: ' + forbidden)
    else:
        required_code.extend(('Ldev/gdirect/lite/auth/AuthorizationCallback;', 'Ldev/gdirect/lite/diagnostics/AppDiagnostics;',
                              'Ldev/gdirect/lite/GoogleNetwork;', 'Ldev/gdirect/lite/network/GoogleBootstrap;', 'Ldev/gdirect/lite/network/DirectRoutes;',
                              'Ldev/gdirect/lite/network/GoogleHttp;', 'Ldev/gdirect/lite/network/GoogleAddressSpace;',
                              'Ldev/gdirect/lite/network/GoogleRangeCatalog;', 'Ldev/gdirect/lite/network/GoogleFrontendScan;', 'Ldev/gdirect/lite/network/IpLiteral;',
                              'Ldev/gdirect/lite/network/GoogleCertificatePolicy;', 'Ldev/gdirect/lite/network/GoogleTrust;', 'Ldev/gdirect/lite/transfer/UploadTransfer;',
                              'Ldev/gdirect/lite/FilePreviewActivity;'))
    if not args.probe:
        source_root = Path(__file__).resolve().parents[1] / 'app/src/main/java/dev/gdirect/lite'
        # A package move must not leave old incremental-build classes in the installable APK.
        for helper in source_root.glob('*/*.java'):
            if (source_root / helper.name).exists():
                continue  # An explicit compatibility entrypoint is intentional.
            obsolete = ('Ldev/gdirect/lite/' + helper.stem + ';').encode('ascii')
            if any(obsolete in part for part in dex):
                raise SystemExit('Obsolete pre-organization class is still packaged: ' + helper.stem)
    for value in required_code:
        if not any(value.encode('ascii') in part for part in dex):
            raise SystemExit('Expected diagnostic code/revision not present in APK: ' + value)
certificates = command(args.apksigner, 'verify', '--print-certs', str(args.apk))
sha1 = re.findall(r'Signer #\d+ certificate SHA-1 digest: ([a-fA-F0-9]{40})', certificates)
if not sha1:
    raise SystemExit('APK certificate fingerprint unavailable')
sha1 = [':'.join(value[i:i+2] for i in range(0,len(value),2)).upper() for value in sha1]
if args.preview:
    pin = json.loads((Path(__file__).resolve().parents[1] / 'config/preview-signing.json').read_text())
    if sha1 != [pin['certificateSha1']] or args.signing_mode != 'pinned-test':
        raise SystemExit('Preview certificate is not the retained pinned test key; refusing delivery')
    if pin['package'] != expected_package or "application-label:'RouteShelf'" not in badging:
        raise SystemExit('Preview identity/launcher label does not match the coexisting application')
prefix = 'GDirectLite-inspector' if args.probe else ('RouteShelf-preview' if args.preview else 'RouteShelf')
filename = f'{prefix}-{package[3]}-{args.revision}-{args.run}.apk'
expected_files = {filename, 'APK-IDENTITY.json', 'signing-certificate.txt', 'SHA256SUMS'}
if args.output.exists() and any(path.is_dir() or path.name not in expected_files for path in args.output.iterdir()):
    raise SystemExit('Output directory contains a different/stale build; select an empty output directory')
args.output.mkdir(parents=True, exist_ok=True)
destination = args.output / filename
shutil.copyfile(args.apk, destination)
sha256 = hashlib.sha256(destination.read_bytes()).hexdigest()
identity = dict(package=package[1], versionCode=int(package[2]), versionName=package[3],
                revision=args.revision, run=args.run, filename=filename,
                signingMode=args.signing_mode, certificateSha1=sha1, apkSha256=sha256,
                oldCancellationMessagePresent=False)
if not args.probe:
    identity['applicationLabel'] = 'RouteShelf'
if re.fullmatch(r'[0-9a-f]{40}', args.revision):
    server = os.environ.get('GITHUB_SERVER_URL', 'https://github.com')
    repository = os.environ.get('GITHUB_REPOSITORY', 'qingzhuernest/routeshelf-java')
    identity['sourceURL'] = f'{server}/{repository}/tree/{args.revision}'
if args.probe:
    identity.update(offlinePackageInspector=True, internetPermission=False, targetPackage='dev.gdirect.lite')
if args.preview:
    identity.update(coexistsWith='dev.gdirect.lite', requiresOwnAndroidOAuthClient=True)
(args.output / 'APK-IDENTITY.json').write_text(json.dumps(identity, indent=2) + '\n')
(args.output / 'signing-certificate.txt').write_text(certificates)
(args.output / 'SHA256SUMS').write_text(f'{sha256}  {filename}\n')
print(json.dumps(identity, ensure_ascii=False, indent=2))
# Only public certificate/build metadata enters the GitHub annotation, never private keys.
print(f'::notice title=Verified APK identity::{package[3]} | {args.revision} | SHA-1 {", ".join(sha1)} | {args.signing_mode}')
