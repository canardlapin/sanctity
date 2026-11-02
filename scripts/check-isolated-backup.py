#!/usr/bin/env python3
"""Qualify packaged MVCC backups and a separately permissioned, source-free store."""
import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid

PYTHON_IMAGE = 'python@sha256:2d44738ebf4ce5f63b9d9ea871e9c7cfdc0426f7b0c97cac915fd88ded1d86bb'
HERE = Path(__file__).resolve().parent

def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE/filename)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value

compat = module('compat', 'check-upgrade-compatibility.py')
store = module('store', 'backup-store.py')

@contextlib.contextmanager
def source_fixture(artifact, root, output):
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
        source = root/'source'; source.mkdir()
        (source/'dataset_description.json').write_text('{"Name":"Backup synthetic","BIDSVersion":"1.10.0","DatasetType":"raw"}')
        (source/'README').write_bytes(b'Synthetic backup fixture.\n' * 90000)
        imported = f.cli('repository','import',principal,dataset,str(source),str(f.storage),'backup-fixture')
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
                f.env['SANCTITY_POLICY_CONFIG'] = str(clients['writer'])
                yield f, principal, dataset, imported, witness_process
            finally:
                f.stop()
                witness_process.terminate()
                try: witness_process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    witness_process.kill(); witness_process.wait()


def run(args, *, input=None, env=None, fail=False, timeout=90):
    p = subprocess.run([str(x) for x in args], input=input, env=env, capture_output=True, timeout=timeout)
    assert (p.returncode != 0) if fail else (p.returncode == 0), 'fixture command failed: '+Path(args[0]).name+': '+(p.stdout+p.stderr).decode(errors='replace')[:2200]
    return p.stdout


def wait_file(path, process):
    deadline = time.monotonic()+30
    while not path.exists():
        assert process.poll() is None, 'capture exited before exported snapshot'
        assert time.monotonic() < deadline, 'capture snapshot timeout'
        time.sleep(.1)


@contextlib.contextmanager
def isolated_store(root, output):
    suffix = uuid.uuid4().hex
    network = 'sanctity-backup-'+suffix
    volumes = {x: network+'-'+x for x in ['store','trust','recovery','reader']}
    writer, reader = uuid.uuid4().hex+uuid.uuid4().hex, uuid.uuid4().hex+uuid.uuid4().hex
    server = None
    try:
        run(['docker','network','create',network])
        for volume in volumes.values(): run(['docker','volume','create',volume])
        mounts = [item for kind, volume in volumes.items() for item in ['-v',volume+':/'+kind]]
        setup = '''import os,json,sys
from pathlib import Path
v=json.load(sys.stdin)
for directory,uid in [('store',10004),('trust',10004),('recovery',10005),('reader',10005)]:
 p=Path('/'+directory);os.chown(p,uid,uid);p.chmod(0o700)
for name,value,uid in [('trust/writer',v['writer'],10004),('trust/reader',v['reader'],10004),('reader/token',v['reader'],10005)]:
 p=Path('/'+name);p.write_text(value);os.chown(p,uid,uid);p.chmod(0o600)
'''
        run(['docker','run','--rm','-i',*mounts,PYTHON_IMAGE,'python','-c',setup],input=json.dumps({'writer':writer,'reader':reader}).encode())
        script = str(HERE/'backup-store.py')+':/app/backup-store.py:ro'
        server = run(['docker','run','-d','--user','10004:10004','--network',network,'--network-alias','store',
                      '--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','-p','127.0.0.1::8080',
                      '-v',volumes['store']+':/store','-v',volumes['trust']+':/trust:ro','-v',script,
                      PYTHON_IMAGE,'python','/app/backup-store.py','serve','--root','/store','--writer-token','/trust/writer',
                      '--reader-token','/trust/reader','--bind','0.0.0.0','--port','8080']).decode().strip()
        port = run(['docker','port',server,'8080/tcp']).decode().strip().rsplit(':',1)[1]
        url = 'http://127.0.0.1:'+port
        deadline = time.monotonic()+20
        while True:
            try:
                store.request(url,reader,'GET','/v2/inventory',insecure=True);break
            except (OSError,RuntimeError):
                assert time.monotonic()<deadline;time.sleep(.1)
        client = ['docker','run','--rm','--user','10005:10005','--network',network,'--read-only',
                  '--cap-drop=ALL','--security-opt=no-new-privileges',
                  '-v',volumes['recovery']+':/recovery','-v',volumes['reader']+':/reader:ro','-v',script,
                  PYTHON_IMAGE,'python','/app/backup-store.py']
        inspect = json.loads(run(['docker','inspect',server]))[0]
        assert {m['Destination'] for m in inspect['Mounts']} == {'/store','/trust','/app/backup-store.py'}
        assert not any(x.startswith(('SANCTITY_','PG')) for x in inspect['Config']['Env'])
        (output/'isolation.json').write_text(json.dumps({'serverUid':inspect['Config']['User'],
            'serverMounts':[{'destination':m['Destination'],'type':m['Type'],'writable':m['RW']} for m in inspect['Mounts']],
            'readerUid':'10005:10005','readerMounts':['/recovery','/reader','/app/backup-store.py'],
            'sourceStorageMounted':False,'sourceDatabaseCredentialsPassed':False,'transport':'HTTP on isolated local test network'},indent=2)+'\n')
        yield url,writer,reader,client,server,volumes
        logs = run(['docker','logs',server])
        assert writer.encode() not in logs and reader.encode() not in logs and compat.PASSWORD.encode() not in logs
        (output/'store.log').write_bytes(logs)
    finally:
        if server: subprocess.run(['docker','rm','-fv',server],capture_output=True,timeout=30)
        for volume in volumes.values(): subprocess.run(['docker','volume','rm',volume],capture_output=True,timeout=30)
        subprocess.run(['docker','network','rm',network],capture_output=True,timeout=30)


def qualify(artifact, root, output, checks):
    captures = root/'captures'; captures.mkdir(mode=0o700)
    with source_fixture(artifact,root,output) as (f,principal,dataset,imported,witness):
        dump = root/'pg_dump'; marker=root/'snapshot-ready'; release=root/'snapshot-release'
        normal = '#!/bin/sh\nexec docker exec -i -e PGUSER -e PGPASSWORD -e PGDATABASE '+f.container+' pg_dump "$@"\n'
        dump.write_text(normal);dump.chmod(0o700);f.env['SANCTITY_PG_DUMP']=str(dump)
        def capture(name,fail=False):
            p=subprocess.run([str(f.launch),'backup','capture',str(f.storage),str(captures/name),str(artifact)],
                             cwd=root,env=f.env,capture_output=True,timeout=90)
            if (p.returncode == 0) == fail:
                (output/(name+'-failure.log')).write_bytes(p.stdout+p.stderr)
                raise AssertionError('capture outcome differs: '+name)
            if fail:
                assert not (captures/name).exists();return None
            return json.loads(p.stdout[p.stdout.index(b'{'):])
        # Pause pg_dump after the snapshot is exported, then publish a real policy change.
        old_revision=f.sql('SELECT revision FROM organization_policy')
        old_blob_count=int(f.sql('SELECT count(*) FROM blob'))
        dump.write_text('#!/bin/sh\ntouch "'+str(marker)+'"\nwhile [ ! -f "'+str(release)+'" ]; do sleep 0.1; done\n'+normal.split('\n',1)[1])
        bundle=captures/'concurrent'
        with (output/'concurrent-capture.log').open('wb') as log:
            p=subprocess.Popen([str(f.launch),'backup','capture',str(f.storage),str(bundle),str(artifact)],
                               cwd=root,env=f.env,stdout=log,stderr=subprocess.STDOUT)
            try:
                wait_file(marker,p)
                tokenfile=root/'new-token.json'
                f.cli('repository','issue-credential-once',str(uuid.uuid4()),principal,'600','after-backup-point',str(tokenfile))
                assert int(f.sql('SELECT revision FROM organization_policy'))>int(old_revision)
                # Science continues too: a second immutable release after the pinned point.
                f.start(True)
                token=json.loads(tokenfile.read_bytes())['token']
                correction=f.json('/v1/datasets/'+dataset+'/releases/'+imported['release']+'/corrections',token,
                    {'path':'dataset_description.json','text':'{"Name":"After backup snapshot","BIDSVersion":"1.10.0","DatasetType":"raw"}','reason':'Backup concurrency fixture'},status=201)
                f.json('/v1/candidates/'+correction['candidate']['id']+'/accept',token,method='POST',key='after-backup-snapshot')
                f.stop()
                release.touch();assert p.wait(timeout=90)==0
            finally:
                release.touch()
                if p.poll() is None:p.terminate();p.wait(timeout=15)
        dump.write_text(normal)
        manifest=store.read_json(bundle/'bundle.json');key=store.verified(bundle)
        assert manifest['policyHead']['revision']==old_revision
        assert manifest['artifact']['sha256']==hashlib.sha256(artifact.read_bytes()).hexdigest()
        assert len(manifest['objects'])==old_blob_count
        result=f.cli('backup','verify',str(bundle));assert result['bundle']==key
        checks.append('packaged-snapshot-allows-concurrent-policy-and-scientific-publication')
        old_user,old_password=f.env['SANCTITY_DB_USER'],f.env['SANCTITY_DB_PASSWORD']
        f.env.update(SANCTITY_DB_USER='compat_runtime',SANCTITY_DB_PASSWORD='disposable_runtime_only')
        try:capture('runtime-denied',fail=True)
        finally:f.env.update(SANCTITY_DB_USER=old_user,SANCTITY_DB_PASSWORD=old_password)
        checks.append('ordinary-runtime-database-role-cannot-capture-an-operator-backup')
        for variant in ['missing','corrupt','extra','symlink','unknown-version']:
            bad=captures/('verify-'+variant);shutil.copytree(bundle,bad)
            target=bad/'catalog.dump'
            if variant=='missing':target.unlink()
            elif variant=='corrupt':target.write_bytes(b'wrong')
            elif variant=='extra':(bad/'unexpected').write_bytes(b'extra')
            elif variant=='symlink':target.unlink();target.symlink_to(bundle/'catalog.dump')
            else:
                altered=dict(manifest);altered['version']='unknown-backup-version'
                (bad/'bundle.json').write_bytes(store.canonical(altered))
            f.cli('backup','verify',str(bad),fail=True)
        checks.append('packaged-verifier-refuses-missing-corrupt-extra-aliased-and-unknown-version-bundles')
        # Independently restore the dump in a private database with no ordinary runtime.
        f.sql('CREATE DATABASE backup_probe')
        run(['docker','exec','-i',f.container,'pg_restore','-U','compat','-d','backup_probe','--exit-on-error'],input=(bundle/'catalog.dump').read_bytes())
        restored=run(['docker','exec','-i',f.container,'psql','-X','-qAt','-U','compat','-d','backup_probe','-v','ON_ERROR_STOP=1'],input=b"SET search_path TO sanctity,pg_catalog; UPDATE repository_state SET recovery_required=true; SELECT revision FROM organization_policy; SELECT head FROM dataset; SELECT count(*) FROM blob;").decode().splitlines()
        assert restored==[old_revision,imported['release'],str(old_blob_count)],restored
        checks.append('dump-restores-exact-historical-head-policy-and-object-inventory-into-closed-probe')
        # Interrupted capture leaves only an unpublished attempt; retry is explicit.
        marker.unlink();release.unlink()
        dump.write_text('#!/bin/sh\ntouch "'+str(marker)+'"\nwhile [ ! -f "'+str(release)+'" ]; do sleep 0.1; done\n'+normal.split('\n',1)[1])
        p=subprocess.Popen([str(f.launch),'backup','capture',str(f.storage),str(captures/'killed'),str(artifact)],cwd=root,env=f.env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        try:
            wait_file(marker,p)
            import signal
            os.killpg(p.pid,signal.SIGKILL);p.wait()
        finally:
            release.touch()
            if p.poll() is None:p.kill();p.wait()
        assert not (captures/'killed').exists() and list(captures.glob('.pending-killed-*'))
        dump.write_text(normal);capture('retry')
        checks.append('killed-capture-never-publishes-and-new-attempt-succeeds')
        dump.write_text('#!/bin/sh\nsleep 100\n')
        f.env['SANCTITY_BACKUP_DEADLINE_SECONDS']='10'
        started=time.monotonic()
        try:capture('hung-dump',fail=True)
        finally:
            f.env.pop('SANCTITY_BACKUP_DEADLINE_SECONDS');dump.write_text(normal)
        assert time.monotonic()-started<25
        checks.append('hung-dump-terminated-at-configured-deadline-with-no-published-bundle')
        object_path=next(f.storage.glob('objects/*/*'));saved=object_path.read_bytes()
        try:
            object_path.unlink();capture('missing-source',fail=True)
            object_path.write_bytes(b'x'*len(saved));capture('corrupt-source',fail=True)
        finally:object_path.write_bytes(saved)
        checks.append('missing-or-corrupt-source-object-refuses-publication')
        # The store and recovery reader run as different OS users without source mounts.
        capture('after-source-restoration')
        with isolated_store(root,output) as (url,writer,reader,client,server,volumes):
            request=lambda token,method,path,**kwargs:store.request(url,token,method,path,insecure=True,**kwargs)
            assert request(writer,'GET','/v2/inventory',allowed=(403,))[0]==403
            assert request(reader,'PUT','/v2/pending/'+key+'/catalog.dump',body=b'x',allowed=(403,))[0]==403
            assert request('not-a-valid-token','GET','/v2/inventory',allowed=(403,))[0]==403
            checks.append('writer-cannot-read-reader-cannot-write-and-unknown-token-denied')
            # One complete but corrupt staged file is replaceable; no incomplete bundle is listed.
            request(writer,'PUT','/v2/pending/'+key+'/catalog.dump',body=b'corrupt')
            assert request(writer,'POST','/v2/publish/'+key,body=store.canonical(manifest),allowed=(409,))[0]==409
            assert request(reader,'GET','/v2/inventory')[1]['bundles']==[]
            assert store.push(bundle,url,writer,True)['bundle']==key
            assert store.push(bundle,url,writer,True)['bundle']==key
            assert request(writer,'PUT','/v2/pending/'+key+'/catalog.dump',body=b'x',allowed=(409,))[0]==409
            checks.append('incomplete-and-corrupt-staging-unpublished-retry-verifies-and-published-bytes-immutable')
            # Broken streaming upload cannot become a successful inventory entry.
            unfinished='e'*64
            conn=store.connection(url,True);conn.putrequest('PUT','/v2/pending/'+unfinished+'/catalog.dump')
            conn.putheader('Authorization','Bearer '+writer);conn.putheader('Content-Length','1000000');conn.endheaders();conn.send(b'partial');conn.close()
            inventory=request(reader,'GET','/v2/inventory')[1]['bundles'];assert len(inventory)==1
            assert inventory[0]['bytes']==sum(v['size'] for v in manifest['files'].values()) and inventory[0]['ageSeconds']>=0
            assert inventory[0]['policyHead']==manifest['policyHead']
            checks.append('interrupted-copy-excluded-from-inventory-with-age-bytes-and-policy-head-diagnostics')
            before_url=url
            run(['docker','restart',server]);time.sleep(.5)
            port=run(['docker','port',server,'8080/tcp']).decode().strip().rsplit(':',1)[1]
            url='http://127.0.0.1:'+port
            (output/'restart-endpoint.json').write_text(json.dumps({'hostPortRediscovered':True,'hostPortChanged':before_url!=url})+'\n')
            result=json.loads(run([*client,'pull','--url','http://store:8080','--token-file','/reader/token','--allow-insecure-test','--bundle-id',key,'--output','/recovery/restored']))
            assert result['bundle']==key
            checks.append('store-restart-and-separate-reader-fetch-all-verified-bytes-without-source-mounts')
            # OS identity also prevents the reader opening the store volume directly.
            run(['docker','run','--rm','--user','10005:10005','-v',volumes['store']+':/foreign:ro',PYTHON_IMAGE,
                 'python','-c','from pathlib import Path; list(Path("/foreign").iterdir())'],fail=True)
            checks.append('recovery-os-user-cannot-open-service-volume')
            writerfile=root/'upload-token';writerfile.write_text(writer);writerfile.chmod(0o600)
            command=root/'capture-command.json';command.write_text(json.dumps([str(f.launch),'backup','capture',str(f.storage),'{output}',str(artifact)]));command.chmod(0o600)
            scheduled=run([sys.executable,HERE/'backup-store.py','schedule','--url',url,'--token-file',writerfile,'--allow-insecure-test',
                '--capture-command',command,'--staging-root',captures,'--interval','1','--count','2'],env=f.env,timeout=180)
            (output/'schedule.log').write_bytes(scheduled)
            inventory=request(reader,'GET','/v2/inventory')[1];assert len(inventory['bundles'])==3
            (output/'inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
            checks.append('bounded-two-run-capture-copy-schedule-produces-distinct-verified-bundles')
            # Tampering after publication is not hidden by publication diagnostics.
            target='/store/bundles/'+key+'/catalog.dump'
            run(['docker','exec','--user','10004:10004',server,'python','-c',
                 'from pathlib import Path;p=Path('+repr(target)+');v=p.read_bytes();p.write_bytes(bytes([v[0]^1])+v[1:])'])
            run([*client,'pull','--url','http://store:8080','--token-file','/reader/token','--allow-insecure-test','--bundle-id',key,'--output','/recovery/corrupt'],fail=True)
            run(['docker','exec','--user','10004:10004',server,'python','-c','from pathlib import Path;Path('+repr(target)+').unlink()'])
            run([*client,'pull','--url','http://store:8080','--token-file','/reader/token','--allow-insecure-test','--bundle-id',key,'--output','/recovery/missing'],fail=True)
            checks.append('reader-refuses-corrupted-and-missing-published-files-without-publishing-a-local-restore')
        witness.terminate();witness.wait(timeout=15)
        capture('witness-outage',fail=True)
        checks.append('unavailable-independent-witness-refuses-new-backup-point')
        (output/'bundle-manifest.json').write_bytes((bundle/'bundle.json').read_bytes())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact',type=Path);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();artifact=args.artifact.resolve();output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    checks=[]
    try:
        with tempfile.TemporaryDirectory(prefix='sanctity-backup-') as directory:
            root=Path(directory).resolve();root.chmod(0o700)
            qualify(artifact,root,output,checks)
    except BaseException:
        (output/'receipt.json').write_text(json.dumps({'status':'failed','completedChecks':checks},indent=2)+'\n')
        raise
    receipt={'schemaVersion':1,'status':'passed','artifactSha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),
             'transportScriptSha256':hashlib.sha256((HERE/'backup-store.py').read_bytes()).hexdigest(),
             'checks':checks,'postgresImage':compat.PG,'storeImage':PYTHON_IMAGE,
             'scope':'Synthetic local snapshot and isolated backup publication engineering',
             'limitations':['No off-host or institutional disaster recovery claim.','HTTP is explicitly a local test transport; deployed clients require HTTPS.',
                            'Closed dump probe does not authorize organization restore reopening; O03.7 and O03.8 remain separate gates.']}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({'status':'passed','checks':len(checks)}))


if __name__=='__main__':main()
