#!/usr/bin/env python3
"""Qualify the standalone packaged witness using disposable synthetic keys and files.

Does not enroll or connect a Sanctity catalog. Known RFC8032 signing material is
strictly a test fixture; generated TLS credentials are removed with the fixture.
"""
import argparse
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import uuid
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    artifact = args.artifact.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    password = 'synthetic-package-test-only'
    checks = []
    with tempfile.TemporaryDirectory(prefix='sanctity-witness-package-') as temporary:
        root = Path(temporary).resolve()
        root.chmod(0o700)
        trust = root/'trust'; trust.mkdir(mode=0o700)
        data = root/'data'; data.mkdir(mode=0o700)
        unpack = root/'package'; unpack.mkdir()
        with zipfile.ZipFile(artifact) as z:
            z.extractall(unpack)
            for name in z.namelist():
                if name.endswith('.jar'):
                    with zipfile.ZipFile(io.BytesIO(z.read(name))) as jar:
                        assert not any('/PolicyWitnessProcess' in entry for entry in jar.namelist())
        launcher = next(unpack.glob('*/bin/sanctity')); launcher.chmod(0o755)
        keytool = str(Path(os.environ['JAVA_HOME'])/'bin/keytool') if os.environ.get('JAVA_HOME') else shutil.which('keytool')
        openssl = shutil.which('openssl')
        assert keytool and openssl
        def run(command, ok=True):
            p = subprocess.run([str(x) for x in command], capture_output=True, text=True, timeout=30)
            if ok and p.returncode:
                raise RuntimeError('synthetic fixture or packaged command failed: '+p.stderr[:1000])
            return p
        fingerprints = {}
        for who in ['server','writer','recovery']:
            run([keytool,'-genkeypair','-alias',who,'-keyalg','EC','-groupname','secp256r1','-dname','CN='+who,
                 '-validity','2','-ext','SAN=dns:localhost','-keystore',trust/(who+'.p12'),'-storetype','PKCS12',
                 '-storepass',password,'-keypass',password,'-noprompt'])
            run([keytool,'-exportcert','-alias',who,'-keystore',trust/(who+'.p12'),'-storepass',password,'-file',trust/(who+'.der')])
            run([keytool,'-exportcert','-rfc','-alias',who,'-keystore',trust/(who+'.p12'),'-storepass',password,'-file',trust/(who+'-cert.pem')])
            run([keytool,'-importcert','-alias',who,'-file',trust/(who+'.der'),'-keystore',trust/'anchors.p12',
                 '-storetype','PKCS12','-storepass',password,'-noprompt'])
            fingerprints[who] = sha((trust/(who+'.der')).read_bytes())
        run([openssl,'pkcs12','-in',trust/'recovery.p12','-out',trust/'recovery.pem','-nodes','-passin','pass:'+password])
        (trust/'signing-private.der').write_bytes(bytes.fromhex('302e020100300506032b657004220420'+'9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60'))
        (trust/'signing-public.der').write_bytes(bytes.fromhex('302a300506032b6570032100'+'d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'))
        (trust/'password').write_text(password)
        with socket.socket() as probe:
            probe.bind(('127.0.0.1',0)); port=probe.getsockname()[1]
        installation=str(uuid.uuid4())
        config={'installation':installation,'store':str(data/'journal'),'identityStore':str(trust/'server.p12'),
                'identityPasswordFile':str(trust/'password'),'trustStore':str(trust/'anchors.p12'),
                'trustPasswordFile':str(trust/'password'),'signingPrivate':str(trust/'signing-private.der'),
                'signingPublic':str(trust/'signing-public.der'),'writerFingerprint':fingerprints['writer'],
                'recoveryFingerprint':fingerprints['recovery'],'port':str(port)}
        config_path=trust/'witness.json'; config_path.write_text(json.dumps(config))
        for p in trust.iterdir(): p.chmod(0o600)
        def cli(action, path=config_path, ok=True): return run([launcher,'policy-witness',action,path],ok)
        cli('init'); checks.append('explicit-init-with-protected-external-trust')
        assert cli('init',ok=False).returncode != 0
        checks.append('existing-store-refuses-reinitialization')
        inspection=json.loads(cli('inspect').stdout)
        assert inspection['enrolled'] is False and inspection['ready'] is False and inspection['sequence']=='0'
        assert not any(name in json.dumps(inspection) for name in [str(root),password,'synthetic-package'])
        checks.append('unenrolled-store-is-not-ready-and-diagnostics-are-redacted')
        config_path.chmod(0o644)
        assert cli('inspect',ok=False).returncode != 0
        config_path.chmod(0o600)
        alias=trust/'symlink.json'; alias.symlink_to(config_path)
        assert cli('inspect',alias,False).returncode != 0
        bad=trust/'unknown.json'; bad.write_text(json.dumps(config|{'unknown':'reject'})); bad.chmod(0o600)
        assert cli('inspect',bad,False).returncode != 0
        checks.append('unsafe-permissions-symlinks-and-unknown-config-rejected')
        context=ssl.create_default_context(cafile=str(trust/'server-cert.pem'))
        context.load_cert_chain(str(trust/'recovery.pem'))
        context.minimum_version=ssl.TLSVersion.TLSv1_3
        env=dict(os.environ)
        # The witness command must not depend on any catalog/database/blob configuration.
        env={k:v for k,v in env.items() if not k.startswith('SANCTITY_')}
        with (output/'service.log').open('w') as log:
            process=subprocess.Popen([str(launcher),'policy-witness','serve',str(config_path)],stdout=log,stderr=subprocess.STDOUT,env=env)
            try:
                deadline=time.monotonic()+15
                while time.monotonic()<deadline:
                    if process.poll() is not None: raise RuntimeError('packaged witness exited during startup')
                    try:
                        with socket.create_connection(('127.0.0.1',port),timeout=.2): break
                    except OSError: time.sleep(.05)
                else: raise RuntimeError('packaged witness did not listen')
                envelope={'version':'sanctity-policy-wire-v1','installation':installation,'challenge':str(uuid.uuid4()),
                          'request':{'action':'CURRENT','command':{},'expectedSequence':'0','expectedHash':'0'*64}}
                body=json.dumps(envelope,sort_keys=True,separators=(',',':')).encode()
                connection=http.client.HTTPSConnection('localhost',port,context=context,timeout=3)
                try:
                    connection.request('POST','/v1/policy',body,{'Content-Type':'application/json'})
                    response=connection.getresponse(); assert response.status==200
                    signed=json.loads(response.read())
                finally: connection.close()
                bound=signed['signed']['body']
                assert bound['challenge']==envelope['challenge'] and bound['requestDigest']==sha(body)
                assert bound['result']['current']['enrolled'] is False
                assert signed['signed']['key']==sha((trust/'signing-public.der').read_bytes())
                assert signed['signature']
                checks.append('packaged-loopback-mutual-tls-current-challenge-without-catalog-configuration')
            finally:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(); raise
        cli('inspect'); checks.append('packaged-process-restart-verifies-store')
        (data/'journal'/'head.json').write_text('{}')
        failed=cli('inspect',ok=False); assert failed.returncode != 0
        assert str(root) not in failed.stdout+failed.stderr and password not in failed.stdout+failed.stderr
        checks.append('corrupt-head-fails-closed-with-redacted-error')
        configuration={'installation':installation,'fileMode':'0600','directoryMode':'0700','host':'127.0.0.1',
                       'tls':'TLSv1.3','certificateFingerprints':fingerprints,
                       'configurationSha256':sha(config_path.read_bytes()),
                       'signingPublicSha256':sha((trust/'signing-public.der').read_bytes()),
                       'signingFixture':'RFC8032 test vector 1; public known test key, never deployment material'}
    result={'schemaVersion':1,'status':'passed','artifactSha256':sha(artifact.read_bytes()),'checks':checks,
            'configuration':configuration,'signatureVerification':'Ed25519 verification is qualified in source-bound PolicyWireSuite and PolicyTransportSuite; this package smoke checks the signed response envelope and TLS binding.',
            'limitations':['No catalog enrollment or SQL adapter exercised.','No separate operating-system accounts, off-host or physical power-loss qualification.']}
    (output/'receipt.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'status':'passed','checks':len(checks),'artifactSha256':result['artifactSha256']}))

if __name__=='__main__': main()
