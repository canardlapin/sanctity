#!/usr/bin/env python3
"""Exercise packaged organization recovery using only disposable synthetic inputs."""
import argparse
import contextlib
import datetime
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
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('backup', HERE/'check-isolated-backup.py')
backup = importlib.util.module_from_spec(spec); spec.loader.exec_module(backup)
compat = backup.compat

def qualify(predecessor, artifact, root, output, checks, harness=None, isolated=False):
    with contextlib.ExitStack() as cleanup:
        scenario = backup.module('source_loss','organization-recovery-isolation.py').Scenario(root,output,checks,backup,harness) if isolated else None
        if scenario: cleanup.callback(scenario.close)
        with zipfile.ZipFile(artifact) as z:
            z.extractall(root/'consumer')
        launch = next((root/'consumer').glob('*/bin/sanctity')); launch.chmod(0o755)
        with backup.source_fixture(predecessor, root, output,scenario.prepare_source if scenario else None) as (f, owner, dataset, imported, old_witness):
            trust = root/'trust'; password = (trust/'password').read_text()
            keytool = shutil.which('keytool')
            for args in [
                ['-genkeypair','-alias','next','-keyalg','EC','-groupname','secp256r1','-dname','CN=next',
                 '-validity','2','-keystore',trust/'next.p12','-storetype','PKCS12','-storepass',password,'-keypass',password,'-noprompt'],
                ['-exportcert','-alias','next','-keystore',trust/'next.p12','-storepass',password,'-file',trust/'next.der'],
                ['-importcert','-alias','next','-file',trust/'next.der','-keystore',trust/'anchors.p12','-storetype','PKCS12','-storepass',password,'-noprompt']]:
                compat.run([str(x) for x in [keytool,*args]])
            next_config = json.loads((trust/'writer.json').read_text()); next_config['identityStore'] = str(trust/'next.p12')
            (trust/'next.json').write_text(json.dumps(next_config))
            for path in trust.iterdir(): path.chmod(0o600)
            fingerprint = hashlib.sha256((trust/'next.der').read_bytes()).hexdigest()
            witness_config = json.loads((trust/'witness.json').read_text())
            old_witness.terminate(); old_witness.wait(timeout=15)
            witness = None
            witness_log = (output/'consumer-witness.log').open('wb')
            def start_witness():
                nonlocal witness
                if scenario:
                    witness = scenario.witness(launch,witness_config,trust,root/'journal',witness_log)
                else:
                    witness = subprocess.Popen([str(launch),'policy-witness','serve',str(trust/'witness.json')],
                        cwd=root,env={k:v for k,v in f.env.items() if k in ['PATH','HOME','LANG']},stdout=witness_log,stderr=subprocess.STDOUT)
                deadline = time.monotonic()+20
                while True:
                    assert witness.poll() is None, 'candidate witness exited'
                    try:
                        with socket.create_connection(('127.0.0.1',int(witness_config['port'])),timeout=.2): pass
                        if scenario:
                            ready = subprocess.run(['java','-cp',str(launch.parent.parent/'lib'/'*')+os.pathsep+str(harness),
                                'sanctity.postgres.RecoveryWitnessReadiness',str(trust/'recovery.json')],
                                cwd=root,env={k:v for k,v in f.env.items() if k in ['PATH','HOME','LANG']},capture_output=True,timeout=10)
                            if ready.returncode != 0:
                                (output/'last-witness-readiness-failure.log').write_bytes(ready.stdout+ready.stderr)
                                assert time.monotonic()<deadline; time.sleep(.2); continue
                        break
                    except OSError:
                        assert time.monotonic()<deadline; time.sleep(.1)
            try:
                start_witness()
                original_http = f.http
                def observed_http(path,token=None,body=None,method=None,key=None):
                    response = original_http(path,token,body,method,key)
                    if response[0] >= 400:
                        with (output/'api-denials.jsonl').open('a') as log:
                            log.write(json.dumps({'path':path,'status':response[0],'body':response[1].decode(errors='replace')[:3000]})+'\n')
                    return response
                f.http = observed_http
                shutil.copytree(root/'journal',root/'initial-journal')
                def credential(principal, label):
                    path = root/(uuid.uuid4().hex+'.json')
                    f.cli('repository','issue-credential-once',str(uuid.uuid4()),principal,'3600',label,str(path))
                    return json.loads(path.read_text())
                owner_credential = credential(owner,'pre-backup-owner')
                f.start(True)
                recipient = str(uuid.uuid4())
                grant = f.json('/v1/datasets/'+dataset+'/grants',owner_credential['token'],
                    {'recipient':recipient,'release':None,'actions':['ReadMetadata','ReadContent']},status=201)
                recipient_credential = credential(recipient,'pre-backup-recipient')
                release_path = '/v1/datasets/'+dataset+'/releases/'+imported['release']
                assert f.http(release_path,recipient_credential['token'])[0] == 200
                def work(action, predecessor_runtime=False):
                    runtime = f.launch.parent.parent if predecessor_runtime else launch.parent.parent
                    configured = f.env if predecessor_runtime else env
                    config = trust/('writer.json' if predecessor_runtime else 'next.json')
                    p = subprocess.run(['java','-cp',str(runtime/'lib'/'*')+os.pathsep+str(harness),
                        'sanctity.postgres.RecoveryWorkQualification',action,str(config),owner,dataset,str(root/'work-leases.json')],
                        cwd=root,env=configured,capture_output=True,timeout=60)
                    (output/('work-'+action+'.log')).write_bytes(p.stdout+p.stderr)
                    assert p.returncode == 0, 'work qualification failed: '+action
                    return json.loads(p.stdout)
                if scenario: scenario.prepare(f,owner,dataset,imported,credential,owner_credential['token'])
                work('setup',True)
                science_tables = ['release','candidate','manifest','interpretation','assessment','acceptance_receipt','audit','acquired_file']
                oracle = {table:f.rows('SELECT * FROM '+table) for table in science_tables}
                readme_identity = (len((root/'source'/'README').read_bytes()),hashlib.sha256((root/'source'/'README').read_bytes()).hexdigest())
                for name in ['pg_dump','pg_restore','psql']:
                    path = root/name
                    path.write_text('#!/bin/sh\nexec docker exec -i -e PGUSER -e PGPASSWORD -e PGDATABASE '+f.container+' '+name+' "$@"\n')
                    path.chmod(0o700)
                    f.env['SANCTITY_'+name.upper()] = str(path)
                captures = root/'captures'; captures.mkdir(mode=0o700); bundle = captures/'baseline'
                f.cli('backup','capture',str(f.storage),str(bundle),str(predecessor))
                checks.append('qualified-schema13-predecessor-backup-captured')
                f.json('/v1/datasets/'+dataset+'/grants/'+grant['id'],owner_credential['token'],method='DELETE',status=204)
                f.cli('repository','revoke-credential',recipient_credential['credential'])
                placement = f.json('/v1/datasets/'+dataset+'/placement',owner_credential['token'])
                project = placement['project']
                project_view = f.json('/v1/projects/'+project,owner_credential['token'])
                missing_data = f.json('/v1/projects/'+project+'/datasets',owner_credential['token'],
                    {'custodian':f.sql("SELECT custodian::text FROM dataset WHERE id='"+dataset+"'"),'expected':{'project:'+project:project_view['generation']}},
                    key='post-backup-data',status=201)['created']['dataset']
                missing_grant = f.json('/v1/datasets/'+missing_data+'/grants',owner_credential['token'],
                    {'recipient':recipient,'release':None,'actions':['ReadMetadata']},status=201)
                f.json('/v1/datasets/'+missing_data+'/grants/'+missing_grant['id'],owner_credential['token'],method='DELETE',status=204)
                correction_base = '/v1/datasets/'+dataset+'/releases/'+f.sql("SELECT head::text FROM dataset WHERE id='"+dataset+"'")
                correction = f.json(correction_base+'/corrections',owner_credential['token'],
                    {'path':'dataset_description.json','text':'{"Name":"After backup","BIDSVersion":"1.10.0","DatasetType":"raw"}','reason':'Post-backup synthetic release'},status=201)
                new_release = f.json('/v1/candidates/'+correction['candidate']['id']+'/accept',owner_credential['token'],
                    method='POST',key='post-backup-release')['ref']['release']
                if isinstance(new_release,dict): new_release = new_release.get('release',new_release.get('id'))
                missing_fixed = f.json('/v1/datasets/'+dataset+'/grants',owner_credential['token'],
                    {'recipient':recipient,'release':new_release,'actions':['ReadMetadata']},status=201)
                f.json('/v1/datasets/'+dataset+'/grants/'+missing_fixed['id'],owner_credential['token'],method='DELETE',status=204)
                current_revision = f.sql('SELECT revision FROM organization_policy')
                assert f.http(release_path,owner_credential['token'])[0] == 200
                if scenario:
                    scenario.changes(f,owner_credential['token'])
                    current_revision = f.sql('SELECT revision FROM organization_policy')
                    target_container,env,bundle = scenario.isolate(f,bundle)
                    f.env = dict(env)
                    captures = bundle.parent
                else:
                    compat.run(['docker','exec',f.container,'createdb','-U','compat','recovered'])
                    env = dict(f.env); env['SANCTITY_JDBC_URL'] = env['SANCTITY_JDBC_URL'].rsplit('/',1)[0]+'/recovered'
                    target_container = f.container
                target_user = env['SANCTITY_DB_USER']
                storage = root/'recovered-storage'; env['SANCTITY_STORAGE'] = str(storage)
                operation = str(uuid.uuid4())
                def command(*args, fail=False):
                    p = subprocess.run([str(launch),*map(str,args)],cwd=root,env=env,capture_output=True,timeout=120)
                    text = (p.stdout+p.stderr).decode(errors='replace')
                    assert compat.PASSWORD not in text and owner_credential['token'] not in text and recipient_credential['token'] not in text
                    with (output/'operator.log').open('a') as log: log.write(args[1]+': '+text+'\n')
                    if p.returncode != 0 and not fail and harness:
                        if args[1] in ['preview','verify']:
                            diagnostic = [args[1],args[7],args[8],args[2],args[4],args[5],args[6]]
                        else: diagnostic = [args[1],args[4],args[5],args[2],args[3]]
                        d = subprocess.run(['java','-cp',str(launch.parent.parent/'lib'/'*')+os.pathsep+str(harness),
                            'sanctity.postgres.OrganizationRecoveryQualification',*map(str,diagnostic)],cwd=root,env=env,capture_output=True,timeout=120)
                        (output/'diagnostic.log').write_bytes(d.stdout+d.stderr)
                    assert (p.returncode != 0) if fail else (p.returncode == 0), 'recovery command outcome differs: '+str(args[:2])+': '+text[:2400]
                    return json.loads(p.stdout[p.stdout.index(b'{'):]) if not fail and b'{' in p.stdout else None
                def sql(source, database='recovered'):
                    return compat.run(['docker','exec','-i',target_container,'psql','-X','-qAt','-U',target_user,'-d',database,'-v','ON_ERROR_STOP=1'],
                        input=('SET search_path TO sanctity,pg_catalog;\n'+source).encode()).decode().strip()
                def rows(source, database='recovered'):
                    return json.loads(sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb) FROM ("+source+") t;",database))
                args = [bundle,predecessor,storage,operation,owner,trust/'recovery.json',trust/'next.json']
                # All input rejections precede the target import and barrier.
                wrong = list(args); wrong[1] = artifact
                command('recovery','preview',*wrong,fail=True)
                corrupted = captures/'corrupt'; shutil.copytree(bundle,corrupted)
                object_path = next((corrupted/'storage'/'objects').glob('*/*'))
                object_path.write_bytes(b'changed synthetic bytes')
                wrong = list(args); wrong[0] = corrupted
                command('recovery','verify',*wrong,fail=True)
                object_path.unlink()
                command('recovery','verify',*wrong,fail=True)
                assert not storage.exists() and sql("SELECT count(*) FROM pg_namespace WHERE nspname='sanctity'") == '0'
                checks.append('wrong-source-artifact-corrupt-and-missing-objects-refused-before-target-mutation')
                witness.terminate(); witness.wait(timeout=15)
                command('recovery','preview',*args,fail=True)
                saved_journal = root/'saved-journal'; (root/'journal').rename(saved_journal)
                # A consistently signed old prefix cannot cover the captured backup head.
                shutil.copytree(root/'initial-journal',root/'journal'); start_witness()
                command('recovery','preview',*args,fail=True)
                witness.terminate(); witness.wait(timeout=15); shutil.rmtree(root/'journal')
                for variant in ['truncated','reordered','tampered']:
                    shutil.copytree(saved_journal,root/'journal')
                    records = sorted((root/'journal').glob('record-*.json'))
                    if variant == 'truncated': records[-1].unlink()
                    elif variant == 'reordered':
                        a,b = records[1].read_bytes(),records[2].read_bytes(); records[1].write_bytes(b); records[2].write_bytes(a)
                    else:
                        value = json.loads(records[-1].read_text()); value['signature'] = 'A'*86
                        records[-1].write_text(json.dumps(value))
                    with (output/('witness-'+variant+'.log')).open('wb') as log:
                        refused = subprocess.run([str(launch),'policy-witness','inspect',str(trust/'witness.json')],
                            cwd=root,env=f.env,stdout=log,stderr=subprocess.STDOUT,timeout=30)
                    assert refused.returncode != 0, 'unsafe witness store accepted: '+variant
                    command('recovery','preview',*args,fail=True)
                    shutil.rmtree(root/'journal')
                saved_journal.rename(root/'journal'); start_witness()
                checks.append('unavailable-stale-truncated-reordered-tampered-witness-evidence-refuses-recovery')
                pending = root/'synthetic-pending.json'
                def remote(action):
                    p = subprocess.run(['java','-cp',str(launch.parent.parent/'lib'/'*')+os.pathsep+str(harness),
                        'sanctity.postgres.OrganizationRecoveryQualification',action,str(trust/'recovery.json'),
                        str(trust/'writer.json'),str(pending)],cwd=root,env=env,capture_output=True,timeout=60)
                    (output/('remote-'+action+'.log')).write_bytes(p.stdout+p.stderr)
                    assert p.returncode == 0, 'synthetic remote intent fixture failed'
                remote('prepare')
                command('recovery','preview',*args,fail=True)
                assert not storage.exists()
                remote('abort')
                checks.append('unresolved-prepare-refuses-recovery-without-inventing-an-outcome')
                unsupported = captures/'unsupported'; shutil.copytree(bundle,unsupported)
                manifest = json.loads((unsupported/'bundle.json').read_text())
                manifest['capabilities']['events'].append('UnknownFutureAuthority')
                (unsupported/'bundle.json').write_bytes(compat.canonical(manifest))
                wrong = list(args); wrong[0] = unsupported
                command('recovery','preview',*wrong,fail=True)
                checks.append('unknown-enabled-capability-profile-refused')
                preview = command('recovery','preview',*args)
                assert preview['status'] == 'preview' and not storage.exists()
                assert sql("SELECT count(*) FROM pg_namespace WHERE nspname='sanctity'") == '0'
                checks.append('preview-authenticates-current-history-without-changing-target')
                verified = command('recovery','verify',*args)
                assert verified['status'] == 'verified-closed'
                if scenario: scenario.catalog_verified()
                assert sql('SELECT recovery_required FROM repository_state') == 't'
                assert sql('SELECT max(version) FROM schema_migration') == '14'
                assert {table:rows('SELECT * FROM '+table) for table in science_tables} == oracle
                if not scenario: assert f.http(release_path,owner_credential['token'])[0] == 503
                checks.append('atomic-closed-restore-migration-barrier-drain-and-old-writer-fencing')
                command('recovery','verify',*args)
                command('recovery','capture-policy',root/'legacy-policy.json',fail=True)
                work('closed')
                # A least privilege login cannot mutate operator recovery state or start the API.
                sql("CREATE ROLE closed_runtime LOGIN PASSWORD 'disposable_runtime_only'; REVOKE CREATE,TEMPORARY ON DATABASE recovered FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC;")
                command('migrate','--runtime-role','closed_runtime')
                runtime_env = dict(env,SANCTITY_DB_USER='closed_runtime',SANCTITY_DB_PASSWORD='disposable_runtime_only',SANCTITY_POLICY_CONFIG=str(trust/'next.json'))
                denied = subprocess.run([str(launch),'serve'],cwd=root,env=runtime_env,capture_output=True,timeout=30)
                assert denied.returncode != 0
                (output/'closed-startup.log').write_bytes(denied.stdout+denied.stderr)
                for table in ['organization_recovery_session','organization_recovery_tombstone']:
                    assert sql("SELECT has_table_privilege('closed_runtime','"+table+"','INSERT,UPDATE,DELETE,TRUNCATE')") == 'f'
                operator_env = dict(env); env.update(SANCTITY_DB_USER='closed_runtime',SANCTITY_DB_PASSWORD='disposable_runtime_only')
                command('recovery','reconcile',operation,storage,trust/'recovery.json',trust/'next.json',fail=True)
                env.clear(); env.update(operator_env)
                checks.append('closed-catalog-denies-data-workers-api-startup-runtime-recovery-and-legacy-capture')
                def crash(stage, phase, closed):
                    assert harness, 'qualification crash harness is required'
                    marker = root/('boundary-'+stage)
                    with (output/('crash-'+stage+'.log')).open('wb') as log:
                        process = subprocess.Popen(['java','-cp',str(launch.parent.parent/'lib'/'*')+os.pathsep+str(harness),
                            'sanctity.postgres.OrganizationRecoveryQualification','reconcile',str(trust/'recovery.json'),
                            str(trust/'next.json'),operation,str(storage),stage,str(marker)],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
                        try:
                            deadline = time.monotonic()+45
                            while not marker.exists():
                                assert process.poll() is None, 'recovery exited before crash boundary '+stage
                                assert time.monotonic()<deadline, 'recovery crash boundary timeout '+stage
                                time.sleep(.05)
                            process.kill(); process.wait(timeout=15)
                        finally:
                            if process.poll() is None: process.kill(); process.wait(timeout=15)
                    assert sql('SELECT phase FROM organization_recovery_session') == phase
                    assert sql('SELECT recovery_required FROM repository_state') == ('t' if closed else 'f')
                    checks.append('process-kill-'+stage+'-retains-'+phase)
                for stage,phase in [('before-apply','Verified'),('after-apply','Applied'),('before-release','Applied'),
                                    ('after-release-before-receipt','Applied'),('after-release-receipt','Released'),('before-open','Released')]:
                    crash(stage,phase,True)
                # Release succeeds before new identity admission; failure must remain closed.
                command('recovery','reconcile',operation,storage,trust/'recovery.json',trust/'next.json',fail=True)
                assert sql('SELECT phase FROM organization_recovery_session') == 'Released'
                assert sql('SELECT recovery_required FROM repository_state') == 't'
                checks.append('new-writer-not-admitted-leaves-signed-release-durable-and-catalog-closed')
                witness.terminate(); witness.wait(timeout=15)
                witness_config['writerFingerprint'] = fingerprint
                (trust/'witness.json').write_text(json.dumps(witness_config)); start_witness()
                crash('after-open','Opened',False)
                opened = command('recovery','reconcile',operation,storage,trust/'recovery.json',trust/'next.json')
                assert opened['phase'] == 'Opened' and opened['admissionReady'] and not opened['closed']
                if scenario: scenario.catalog_opened()
                assert sql('SELECT revision FROM organization_policy') == current_revision
                assert {table:rows('SELECT * FROM '+table) for table in science_tables} == oracle
                receipt = opened['verification']
                assert receipt['scientificDigestBefore'] == receipt['scientificDigestAfter']
                assert sql('SELECT count(*) FROM api_credential WHERE revoked_at IS NULL') == '0'
                assert sql("SELECT revoked_at IS NOT NULL FROM access_grant WHERE id='"+grant['id']+"'") == 't'
                assert rows('SELECT kind,target::text FROM organization_recovery_tombstone ORDER BY kind') == [
                    {'kind':'dataset','target':missing_data},{'kind':'release','target':new_release}]
                assert sql("SELECT count(*) FROM dataset WHERE id='"+missing_data+"'") == '0'
                assert sql("SELECT count(*) FROM release WHERE id='"+new_release+"'") == '0'
                for table,identifier in [('dataset',missing_data),('release',new_release)]:
                    rejected = subprocess.run(['docker','exec','-i',target_container,'psql','-X','-qAt','-U',target_user,'-d','recovered','-v','ON_ERROR_STOP=1'],
                        input=("SET search_path TO sanctity,pg_catalog; INSERT INTO "+table+" SELECT (jsonb_populate_record(NULL::"+table+",to_jsonb(d)||jsonb_build_object('id','"+identifier+"'))).* FROM "+table+" d LIMIT 1;").encode(),capture_output=True,timeout=20)
                    assert rejected.returncode != 0 and b'scientific identity is retired by recovery' in rejected.stderr
                checks.append('absent-dataset-and-release-restrictions-retained-and-scientific-id-reuse-refused')
                assert command('recovery','reconcile',operation,storage,trust/'recovery.json',trust/'next.json') == opened
                checks.append('reconcile-preserves-independent-science-oracle-and-applies-post-backup-restrictions')
                work('open')
                checks.append('old-import-and-delivery-leases-denied-and-pending-work-reacquired')
                (output/'recovery-receipt.json').write_text(json.dumps(opened,indent=2)+'\n')
                # Move fixture client to restored service, using a newly governed runtime login.
                f.stop(); f.launch = launch; f.env = env; f.storage = storage
                f.env['SANCTITY_POLICY_CONFIG'] = str(trust/'next.json')
                sql("CREATE ROLE recovered_runtime LOGIN PASSWORD 'disposable_runtime_only'; REVOKE CREATE,TEMPORARY ON DATABASE recovered FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC;")
                f.cli('migrate','--runtime-role','recovered_runtime')
                owner_new = credential(owner,'post-recovery-owner')
                recipient_new = credential(recipient,'post-recovery-recipient')
                if scenario: scenario.before_runtime(f,credential)
                f.env.update(SANCTITY_DB_USER='recovered_runtime',SANCTITY_DB_PASSWORD='disposable_runtime_only')
                f.start(False)
                if scenario: scenario.api_started()
                for old in [owner_credential,recipient_credential]: assert f.http(release_path,old['token'])[0] == 401
                assert f.http(release_path,owner_new['token'])[0] == 200
                denied = f.http(release_path,recipient_new['token'])
                assert denied == f.http('/v1/datasets/'+str(uuid.uuid4())+'/releases/'+str(uuid.uuid4()),recipient_new['token'])
                assert denied[0] == 404
                code,body = f.http(release_path+'/file?path=README',owner_new['token'])
                assert code == 200 and (len(body),hashlib.sha256(body).hexdigest()) == readme_identity
                if scenario: scenario.after_open(f,owner_new['token'],sql,rows)
                checks.append('old-bearers-denied-fresh-governed-credentials-enforce-current-access-and-exact-bytes')
                if not scenario:
                    # A second cycle consumes the exact running schema-14 artifact
                    # and replays the first signed release without reusing its writer.
                    f.stop()
                    cycle_env=dict(operator_env,SANCTITY_POLICY_CONFIG=str(trust/'next.json'))
                    dump=root/'cycle-pg_dump'
                    dump.write_text('#!/bin/sh\nexec docker exec -i -e PGUSER -e PGPASSWORD -e PGDATABASE '+target_container+' pg_dump "$@"\n'); dump.chmod(0o700)
                    cycle_env['SANCTITY_PG_DUMP']=str(dump)
                    env.clear(); env.update(cycle_env); f.env=env
                    cycle_parent=root/'cycle-captures'; cycle_parent.mkdir(mode=0o700)
                    cycle_bundle=cycle_parent/'bundle'
                    f.cli('backup','capture',str(storage),str(cycle_bundle),str(artifact))
                    for options in [
                        ['-genkeypair','-alias','next2','-keyalg','EC','-groupname','secp256r1','-dname','CN=next2','-validity','2',
                         '-keystore',trust/'next2.p12','-storetype','PKCS12','-storepass',password,'-keypass',password,'-noprompt'],
                        ['-exportcert','-alias','next2','-keystore',trust/'next2.p12','-storepass',password,'-file',trust/'next2.der'],
                        ['-importcert','-alias','next2','-file',trust/'next2.der','-keystore',trust/'anchors.p12','-storetype','PKCS12','-storepass',password,'-noprompt']]:
                        compat.run([str(x) for x in [keytool,*options]])
                    next2=dict(next_config,identityStore=str(trust/'next2.p12'))
                    (trust/'next2.json').write_text(json.dumps(next2))
                    for path in trust.iterdir(): path.chmod(0o600)
                    compat.run(['docker','exec',target_container,'createdb','-U',target_user,'recovered_cycle'])
                    env['SANCTITY_JDBC_URL']=env['SANCTITY_JDBC_URL'].rsplit('/',1)[0]+'/recovered_cycle'
                    cycle_storage=root/'cycle-storage'; env['SANCTITY_STORAGE']=str(cycle_storage)
                    cycle_operation=str(uuid.uuid4())
                    cycle_args=[cycle_bundle,artifact,cycle_storage,cycle_operation,owner,trust/'recovery.json',trust/'next2.json']
                    assert command('recovery','preview',*cycle_args)['status']=='preview'
                    assert command('recovery','verify',*cycle_args)['status']=='verified-closed'
                    assert sql('SELECT recovery_required FROM repository_state','recovered_cycle')=='t'
                    witness.terminate(); witness.wait(timeout=15)
                    witness_config['writerFingerprint']=hashlib.sha256((trust/'next2.der').read_bytes()).hexdigest()
                    (trust/'witness.json').write_text(json.dumps(witness_config)); start_witness()
                    cycle=command('recovery','reconcile',cycle_operation,cycle_storage,trust/'recovery.json',trust/'next2.json')
                    assert cycle['phase']=='Opened' and cycle['admissionReady']
                    assert sql('SELECT writer_epoch FROM organization_policy','recovered_cycle')==str(int(opened['verification']['summary']['head']['epoch'])+1)
                    assert sql('SELECT count(*) FROM api_credential WHERE revoked_at IS NULL','recovered_cycle')=='0'
                    assert {table:rows('SELECT * FROM '+table,'recovered_cycle') for table in science_tables}==oracle
                    assert command('recovery','reconcile',cycle_operation,cycle_storage,trust/'recovery.json',trust/'next2.json')==cycle
                    (output/'schema14-cycle-receipt.json').write_text(json.dumps(cycle,indent=2)+'\n')
                    env['SANCTITY_POLICY_CONFIG']=str(trust/'next2.json')
                    sql("CREATE ROLE cycle_runtime LOGIN PASSWORD 'disposable_runtime_only'; REVOKE CREATE,TEMPORARY ON DATABASE recovered_cycle FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC;",'recovered_cycle')
                    f.cli('migrate','--runtime-role','cycle_runtime')
                    cycle_owner=credential(owner,'second-recovery-owner')
                    f.env.update(SANCTITY_DB_USER='cycle_runtime',SANCTITY_DB_PASSWORD='disposable_runtime_only')
                    f.storage=cycle_storage; f.start(False)
                    assert f.http(release_path,owner_new['token'])[0]==401
                    assert f.http(release_path,cycle_owner['token'])[0]==200
                    code,body=f.http(release_path+'/file?path=README',cycle_owner['token'])
                    assert code==200 and (len(body),hashlib.sha256(body).hexdigest())==readme_identity
                    checks.append('schema14-backup-second-recovery-replays-prior-release-and-rotates-another-fresh-writer')

            finally:
                if witness:
                    witness.terminate()
                    try: witness.wait(timeout=15)
                    except subprocess.TimeoutExpired: witness.kill(); witness.wait()
                witness_log.close()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('predecessor',type=Path); parser.add_argument('artifact',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--harness',type=Path)
    parser.add_argument('--isolated',action='store_true')
    args=parser.parse_args(); output=args.output.resolve(); output.mkdir(parents=True,exist_ok=False)
    script_names=['check-organization-recovery.py','organization-recovery-isolation.py','check-isolated-backup.py','backup-store.py','check-upgrade-compatibility.py']
    def input_hashes(): return {name:hashlib.sha256((HERE/name).read_bytes()).hexdigest() for name in script_names}
    script_hashes=input_hashes()
    harness_hash=hashlib.sha256(args.harness.read_bytes()).hexdigest() if args.harness else None
    checks=[]; status='failed'
    try:
        with tempfile.TemporaryDirectory(prefix='sanctity-organization-recovery-') as directory:
            root=Path(directory).resolve(); root.chmod(0o700)
            qualify(args.predecessor.resolve(),args.artifact.resolve(),root,output,checks,args.harness.resolve() if args.harness else None,args.isolated)
            canaries=[]
            for path in root.rglob('*.json'):
                try: value=json.loads(path.read_text())
                except (ValueError,UnicodeError,OSError): continue
                if isinstance(value,dict):
                    canaries.extend(value[key] for key in ['token','importToken','deliveryToken'] if isinstance(value.get(key),str))
            for path in output.rglob('*'):
                if path.is_file(): assert all(secret.encode() not in path.read_bytes() for secret in canaries), 'secret canary in retained evidence'
            checks.append('all-issued-bearer-and-worker-lease-secret-canaries-absent-from-retained-evidence')
        assert input_hashes()==script_hashes, 'qualification inputs changed while running'
        assert not args.harness or hashlib.sha256(args.harness.read_bytes()).hexdigest()==harness_hash, 'qualification harness changed while running'
        status='passed'
    finally:
        (output/'receipt.json').write_text(json.dumps({'status':status,'checks':checks,
            'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'isolated':args.isolated,
            'scripts':script_hashes,'inputFilesUnchanged':input_hashes()==script_hashes,
            'harnessSha256':harness_hash,
            'runtime':{'java':subprocess.run(['java','-version'],capture_output=True).stderr.decode().strip(),'python':sys.version.split()[0]},
            'postgresImage':compat.PG,'scope':'Synthetic local closed recovery and source isolation; no institutional or physical failure-domain acceptance.',
            'predecessorSha256':hashlib.sha256(args.predecessor.read_bytes()).hexdigest(),
            'artifactSha256':hashlib.sha256(args.artifact.read_bytes()).hexdigest()},indent=2)+'\n')
    print(json.dumps({'status':status,'checks':len(checks)}))

if __name__=='__main__': main()
