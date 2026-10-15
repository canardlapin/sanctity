#!/usr/bin/env python3
"""Exercise packaged catalog enrollment and writer configuration against disposable PostgreSQL/TLS."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import uuid
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    artifact = args.artifact.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location('compat_fixture', Path(__file__).with_name('check-upgrade-compatibility.py'))
    compat = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compat)
    checks = []
    with tempfile.TemporaryDirectory(prefix='sanctity-catalog-package-') as directory:
        root = Path(directory).resolve()
        root.chmod(0o700)
        trust = root/'trust'; trust.mkdir(mode=0o700)
        password = 'synthetic-policy-package-only'
        keytool = str(Path(os.environ['JAVA_HOME'])/'bin/keytool') if os.environ.get('JAVA_HOME') else shutil.which('keytool')
        assert keytool
        def command(argv):
            p = subprocess.run([str(x) for x in argv], capture_output=True, timeout=45)
            assert p.returncode == 0, 'synthetic key fixture failed'
        fingerprints = {}
        for who in ['server','writer','recovery']:
            command([keytool,'-genkeypair','-alias',who,'-keyalg','EC','-groupname','secp256r1','-dname','CN='+who,
                     '-validity','2','-ext','SAN=dns:localhost','-keystore',trust/(who+'.p12'),'-storetype','PKCS12',
                     '-storepass',password,'-keypass',password,'-noprompt'])
            command([keytool,'-exportcert','-alias',who,'-keystore',trust/(who+'.p12'),'-storepass',password,'-file',trust/(who+'.der')])
            command([keytool,'-importcert','-alias',who,'-file',trust/(who+'.der'),'-keystore',trust/'anchors.p12',
                     '-storetype','PKCS12','-storepass',password,'-noprompt'])
            fingerprints[who] = hashlib.sha256((trust/(who+'.der')).read_bytes()).hexdigest()
        # Known RFC8032 test vector; never deployment key material.
        (trust/'signing-private.der').write_bytes(bytes.fromhex('302e020100300506032b657004220420'+'9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60'))
        (trust/'signing-public.der').write_bytes(bytes.fromhex('302a300506032b6570032100'+'d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'))
        (trust/'password').write_text(password)
        with compat.Fixture(artifact,root,output/'runtime.log') as f:
            f.cli('migrate')
            principal = str(uuid.uuid4())
            dataset = f.cli('repository','create',principal,str(uuid.uuid4()))['dataset']
            preview = root/'preview.json'
            f.cli('migrate','--organization-preview',principal,str(preview))
            digest = hashlib.sha256(compat.canonical(json.loads(preview.read_text()))).hexdigest()
            f.cli('migrate','--organization-bootstrap',principal,digest)
            installation = f.sql('SELECT installation FROM organization_policy')
            with socket.socket() as s:
                s.bind(('127.0.0.1',0)); port = s.getsockname()[1]
            witness = {'installation':installation,'store':str(root/'journal'), 'identityStore':str(trust/'server.p12'),
                       'identityPasswordFile':str(trust/'password'),'trustStore':str(trust/'anchors.p12'),
                       'trustPasswordFile':str(trust/'password'),'signingPrivate':str(trust/'signing-private.der'),
                       'signingPublic':str(trust/'signing-public.der'),'writerFingerprint':fingerprints['writer'],
                       'recoveryFingerprint':fingerprints['recovery'],'port':str(port)}
            witness_path = trust/'witness.json'; witness_path.write_text(json.dumps(witness))
            clients = {}
            for who in ['writer','recovery']:
                value = {k:v for k,v in witness.items() if k in ['installation','identityPasswordFile','trustStore','trustPasswordFile','signingPublic']}
                value.update(identityStore=str(trust/(who+'.p12')),endpoint=f'https://localhost:{port}/v1/policy')
                clients[who] = trust/(who+'.json'); clients[who].write_text(json.dumps(value))
            for p in trust.iterdir(): p.chmod(0o600)
            f.cli('policy-witness','init',str(witness_path))
            with (output/'witness.log').open('w') as log:
                witness_process = subprocess.Popen([str(f.launch),'policy-witness','serve',str(witness_path)],
                    cwd=root,env=f.env,stdout=log,stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic()+20
                    while True:
                        assert witness_process.poll() is None
                        try:
                            with socket.create_connection(('127.0.0.1',port),timeout=.2): break
                        except OSError:
                            assert time.monotonic()<deadline
                            time.sleep(.1)
                    f.cli('policy','enroll',str(clients['writer']),str(clients['recovery']))
                    assert f.sql('SELECT durability_mode FROM organization_policy') == 'independent'
                    assert f.sql('SELECT recovery_required FROM repository_state') == 'f'
                    checks.append('packaged-closed-enrollment-and-signed-currentness')
                    f.cli('policy','readiness',fail=True)
                    checks.append('missing-independent-trust-refuses-admission')
                    f.env['SANCTITY_POLICY_CONFIG'] = str(clients['writer'])
                    ready = f.cli('policy','readiness')
                    assert ready['mode'] == 'independent' and ready['pending'] is False
                    assert not any(x in json.dumps(ready) for x in [password,str(root),principal])
                    checks.append('protected-writer-config-and-redacted-readiness')
                    operation = str(uuid.uuid4()); tokenfile = root/'credential.json'
                    f.cli('repository','issue-credential-once',operation,principal,'600','retained-id',str(tokenfile))
                    credential = json.loads(tokenfile.read_text())
                    assert credential['credential'] == operation and credential['durability'] == 'Durable'
                    assert tokenfile.stat().st_mode & 0o777 == 0o600
                    assert f.sql('SELECT count(*) FROM policy_acknowledgement') == '1'
                    checks.append('credential-file-follows-verified-independent-commit')
                    f.cli('repository','issue-credential-once',operation,principal,'600','retained-id',str(root/'duplicate.json'),fail=True)
                    assert f.sql('SELECT count(*) FROM api_credential') == '1'
                    assert not (root/'duplicate.json').exists()
                    f.cli('repository','issue-credential',principal,'600','unretained',str(root/'unsafe.json'),fail=True)
                    checks.append('retained-issuance-id-does-not-duplicate-and-random-issuance-is-refused')
                    f.cli('policy','resolve')
                    f.start(modern=True)
                    assert f.http('/v1/me',credential['token'])[0] == 200
                    grant_url = f.base+'/v1/datasets/'+dataset+'/grants'
                    headers = {'Authorization':'Bearer '+credential['token'],'Content-Type':'application/json'}
                    payload = compat.canonical({'recipient':str(uuid.uuid4()),'release':None,'actions':['ReadMetadata']})
                    with urllib.request.urlopen(urllib.request.Request(grant_url,data=payload,headers=headers),timeout=10) as response:
                        assert response.status == 201 and response.headers['X-Sanctity-Policy-Durability'] == 'Durable'
                        grant = json.loads(response.read())
                    with urllib.request.urlopen(urllib.request.Request(grant_url+'/'+grant['id'],headers=headers,method='DELETE'),timeout=10) as response:
                        assert response.status == 204 and response.headers['X-Sanctity-Policy-Durability'] == 'Durable'
                    checks.append('public-grant-and-revoke-explicitly-report-durable-acknowledgement')
                    f.cli('repository','revoke-credential',operation)
                    assert f.http('/v1/me',credential['token'])[0] == 401
                    assert f.sql('SELECT count(*) FROM policy_acknowledgement') == '4'
                    checks.append('fresh-runtime-login-authenticates-and-revocation-denies-current-access')
                    f.stop()
                    clients['writer'].chmod(0o644)
                    f.cli('policy','readiness',fail=True)
                    clients['writer'].chmod(0o600)
                    alias = trust/'writer-alias.json'; alias.symlink_to(clients['writer'])
                    f.env['SANCTITY_POLICY_CONFIG'] = str(alias)
                    f.cli('policy','readiness',fail=True)
                    f.env['SANCTITY_POLICY_CONFIG'] = str(clients['writer'])
                    checks.append('unsafe-writer-config-permissions-and-aliases-refused')
                    f.cli('recovery','capture-policy',str(root/'forbidden.json'),fail=True)
                    checks.append('organization-legacy-recovery-remains-closed')
                finally:
                    f.stop()
                    witness_process.terminate()
                    try: witness_process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        witness_process.kill(); witness_process.wait()
            f.cli('policy','readiness',fail=True)
            checks.append('witness-outage-refuses-new-admission')
    receipt = {'schemaVersion':1,'status':'passed','artifactSha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),
               'checks':checks,'postgresImage':compat.PG,'scope':'Synthetic local engineering; actual packaged service, TLS witness and disposable PostgreSQL',
               'limitations':['No institutional approval or off-host persistence qualification.','No organization replay or restored catalog reopening.']}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({'status':'passed','checks':len(checks),'artifactSha256':receipt['artifactSha256']}))


if __name__ == '__main__':
    main()
