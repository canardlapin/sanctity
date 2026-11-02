"""Additional synthetic source-loss scenario used by check-organization-recovery.py."""
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import struct
import tarfile
import time
import uuid

JRE = 'eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c'

def archive(directory, prefix=''):
    data=io.BytesIO()
    with tarfile.open(fileobj=data,mode='w') as tar:
        tar.add(directory,arcname=prefix or '.',recursive=True)
    return data.getvalue()

def extract(data, directory):
    directory.mkdir(parents=True,exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:') as tar:
        assert all(not Path(m.name).is_absolute() and '..' not in Path(m.name).parts and not m.issym() and not m.islnk() for m in tar.getmembers())
        tar.extractall(directory,filter='data')

class ContainerWitness:
    def __init__(self, scenario, launch, config, trust, journal, log):
        self.scenario=scenario; self.journal=journal; self.synced=False
        run=scenario.run; name=scenario.prefix+'-witness'
        trust_dir=scenario.root/'witness-input'; trust_dir.mkdir(mode=0o700,exist_ok=True)
        for name_ in ['server.p12','password','anchors.p12','signing-private.der','signing-public.der']:
            shutil.copyfile(trust/name_,trust_dir/name_)
        safe=dict(config,store='/work/journal',port='9443')
        for field in ['identityStore','identityPasswordFile','trustStore','trustPasswordFile','signingPrivate','signingPublic']:
            safe[field]='/trust/'+Path(config[field]).name
        (trust_dir/'witness.json').write_text(json.dumps(safe))
        staging=scenario.root/'witness-staging'; shutil.rmtree(staging,ignore_errors=True)
        (staging/'work').mkdir(parents=True); shutil.copytree(journal,staging/'work'/'journal')
        shutil.copytree(trust_dir,staging/'trust')
        mounts=['-v',scenario.volumes['witness-work']+':/work','-v',scenario.volumes['witness-trust']+':/trust']
        run(['docker','run','--rm','-i',*mounts,scenario.image,'sh','-c',
             'find /work /trust -mindepth 1 -delete; tar -xf - -C /; chown -R 10001:10001 /work /trust; chmod 700 /work /trust; find /trust -type f -exec chmod 600 {} +'],input=archive(staging))
        runtime=launch.parent.parent
        for p in [runtime,*runtime.rglob('*')]: p.chmod(0o755 if p.is_dir() or p.name=='sanctity' else 0o644)
        self.name=name
        runtime_mounts=['-v',scenario.volumes['witness-work']+':/work','-v',scenario.volumes['witness-trust']+':/trust:ro']
        self.process=subprocess.Popen(['docker','run','--rm','--name',name,'--user','10001:10001','--read-only',
            '--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,nodev,size=67108864',
            '-p','127.0.0.1:'+config['port']+':9444',*runtime_mounts,'-v',str(runtime)+':/opt/runtime:ro',
            scenario.image,'/opt/runtime/bin/sanctity','policy-witness','serve','/trust/witness.json'],stdout=log,stderr=subprocess.STDOUT)
        # The witness intentionally listens on loopback. A credential-free byte
        # forwarder shares only its network namespace; TLS remains end to end.
        deadline=time.monotonic()+15
        while True:
            state=subprocess.run(['docker','inspect','--format','{{.State.Running}}',name],capture_output=True)
            if state.returncode==0 and state.stdout.strip()==b'true': break
            assert self.process.poll() is None and time.monotonic()<deadline
            time.sleep(.1)
        self.proxy=name+'-proxy'
        forwarder = """import socket,socketserver,threading
class Handler(socketserver.BaseRequestHandler):
 def handle(self):
  try: peer=socket.create_connection(('127.0.0.1',9443),timeout=10)
  except OSError:return
  def copy(a,b):
   try:
    while True:
     data=a.recv(65536)
     if not data:break
     b.sendall(data)
   except OSError:pass
   finally:
    try:b.shutdown(socket.SHUT_WR)
    except OSError:pass
  t=threading.Thread(target=copy,args=(self.request,peer));t.start();copy(peer,self.request);t.join();peer.close()
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
Server(('0.0.0.0',9444),Handler).serve_forever()
"""
        run(['docker','run','-d','--rm','--name',self.proxy,'--network','container:'+name,'--user','10006:10006',
            '--read-only','--cap-drop=ALL','--security-opt=no-new-privileges',scenario.backup.PYTHON_IMAGE,'python','-c',forwarder])
        state=json.loads(run(['docker','inspect',self.name]))[0]
        proxy=json.loads(run(['docker','inspect',self.proxy]))[0]
        assert state['Config']['User']=='10001:10001' and state['HostConfig']['ReadonlyRootfs']
        assert {m['Destination'] for m in state['Mounts']}=={'/work','/trust','/opt/runtime'}
        assert not next(m for m in state['Mounts'] if m['Destination']=='/trust')['RW']
        assert not any(v.startswith(('SANCTITY_','PG')) for v in state['Config']['Env'])
        assert proxy['Config']['User']=='10006:10006' and not proxy['Mounts']
        evidence={'witnessImage':scenario.image_identity,'jreBase':JRE,'witnessUser':state['Config']['User'],
            'readOnlyRoot':state['HostConfig']['ReadonlyRootfs'],'capDrop':state['HostConfig']['CapDrop'],
            'securityOptions':state['HostConfig']['SecurityOpt'],
            'mounts':[{'destination':m['Destination'],'type':m['Type'],'writable':m['RW']} for m in state['Mounts']],
            'publishedPorts':state['HostConfig']['PortBindings'],'sourceDatabaseEnvironmentPresent':False,
            'forwarderUser':proxy['Config']['User'],'forwarderMounts':[],'tlsTerminatedByForwarder':False}
        (scenario.output/'witness-isolation.json').write_text(json.dumps(evidence,indent=2)+'\n')


    def poll(self): return self.process.poll()
    def terminate(self):
        self.scenario.run(['docker','stop','-t','2',self.proxy],fail_ok=True)
        self.scenario.run(['docker','stop','-t','10',self.name],fail_ok=True)
    def kill(self):
        self.scenario.run(['docker','kill',self.proxy],fail_ok=True)
        self.scenario.run(['docker','kill',self.name],fail_ok=True)
    def wait(self,timeout=None):
        result=self.process.wait(timeout=timeout)
        if not self.synced:
            data=self.scenario.run(['docker','run','--rm','--user','10001:10001','-v',self.scenario.volumes['witness-work']+':/work:ro',
                self.scenario.image,'tar','-C','/work/journal','-cf','-','.'])
            shutil.rmtree(self.journal); extract(data,self.journal); self.journal.chmod(0o700)
            self.synced=True
        return result

class Scenario:
    def __init__(self,root,output,checks,backup,harness):
        self.root=root; self.output=output; self.checks=checks; self.backup=backup; self.harness=harness
        self.secrets=[backup.compat.PASSWORD,'disposable_runtime_only','synthetic-policy-package-only']
        self.export_oracle={}; self.grants={}
        self.prefix='sanctity-source-loss-'+uuid.uuid4().hex[:12]
        self.volumes={}; self.target=None; self.store_context=None; self.people={}; self.tokens={}; self.fresh={}; self.acknowledgements=[]
        self.started=time.monotonic()
        build=root/'witness-image'; build.mkdir()
        dockerfile='FROM '+JRE+'\nRUN groupadd -g 10001 witness && useradd -u 10001 -g witness witness\n'
        (build/'Dockerfile').write_text(dockerfile)
        self.image=self.prefix+':witness'
        p=subprocess.run(['docker','build','-t',self.image,str(build)],capture_output=True,timeout=180)
        (output/'witness-image-build.log').write_bytes(p.stdout+p.stderr)
        assert p.returncode==0, 'isolated witness image build failed'
        (output/'witness-Dockerfile').write_text(dockerfile)
        self.image_identity=self.run(['docker','image','inspect',self.image,'--format','{{.Id}}']).decode().strip()
        for kind in ['witness-work','witness-trust']:
            name=self.prefix+'-'+kind; self.run(['docker','volume','create',name]); self.volumes[kind]=name
    def run(self,args,input=None,fail_ok=False):
        p=subprocess.run([str(x) for x in args],input=input,capture_output=True,timeout=90)
        assert fail_ok or p.returncode==0, 'source-loss fixture subprocess failed: '+Path(args[0]).name
        return p.stdout
    def witness(self,launch,config,trust,journal,log): return ContainerWitness(self,launch,config,trust,journal,log)
    def generation(self,f,kind,identifier):
        if kind=='principal': return int(f.sql("SELECT generation FROM principal WHERE id='"+identifier+"'"))
        key='dataset_id' if kind=='placement' else 'id'
        return int(f.sql("SELECT generation FROM organization_"+kind+" WHERE "+key+"='"+identifier+"'"))
    def mutation(self,f,path,token,body,refs,status=200):
        expected={kind+':'+identifier:self.generation(f,kind,identifier) for kind,identifier in refs}
        value=f.json(path,token,dict(body,expected=expected),key=uuid.uuid4().hex,status=status)
        assert value['durability']=='Durable'
        self.acknowledgements.append({'sequence':value['sequence'],'key':value['key'],'durability':value['durability']})
        return value
    def delivery(self,f,action,person,release,destination):
        command=['java','-cp',str(f.launch.parent.parent/'lib'/'*')+os.pathsep+str(self.harness),
            'sanctity.postgres.RecoveryDeliveryQualification',action,f.env['SANCTITY_POLICY_CONFIG'],person,self.dataset,release,str(destination)]
        p=subprocess.run(command,cwd=self.root,env=f.env,capture_output=True,timeout=90)
        label='delivery-'+action+'-'+str(len(list(self.output.glob('delivery-*.log'))))
        (self.output/(label+'.log')).write_bytes(p.stdout+p.stderr)
        assert p.returncode==0, 'delivery qualification failed: '+action
        return json.loads(p.stdout)
    def inventory(self,directory):
        return {p.relative_to(directory).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in sorted(directory.rglob('*')) if p.is_file()}
    def catalog_verified(self): self.catalog_verified_at=time.monotonic()
    def catalog_opened(self): self.catalog_opened_at=time.monotonic()
    def api_started(self): self.api_ready_at=time.monotonic()
    def prepare_source(self,source):
        anatomy=source/'sub-01'/'anat'; anatomy.mkdir(parents=True)
        header=bytearray(352)
        struct.pack_into('<i',header,0,348)
        struct.pack_into('<8h',header,40,3,2,2,2,1,1,1,1)
        struct.pack_into('<hh',header,70,16,32)
        struct.pack_into('<8f',header,76,1,1,1,1,1,1,1,1)
        struct.pack_into('<f',header,108,352)
        header[344:348]=b'n+1\0'
        (anatomy/'sub-01_T1w.nii').write_bytes(header+struct.pack('<8f',*range(8)))
        (anatomy/'sub-01_T1w.json').write_text('{"Manufacturer":"Synthetic fixture","MagneticFieldStrength":3}')
        self.metadata_path='sub-01/anat/sub-01_T1w.nii'
    def prepare(self,f,owner,dataset,imported,credential,owner_token):
        self.owner=owner; self.dataset=dataset; self.first=imported['release']
        placement=f.json('/v1/datasets/'+dataset+'/placement',owner_token); project=placement['project']
        lab=f.json('/v1/projects/'+project,owner_token)['lab']; self.lab=lab; self.project=project
        f.cli('organization','create-lab',owner,owner,'Source-loss second lab','source-loss-lab')
        other_lab=f.sql("SELECT id::text FROM organization_lab WHERE payload->'value'->>'name'='Source-loss second lab'")
        made=self.mutation(f,'/v1/labs/'+other_lab+'/projects',owner_token,{'name':'Second project'},[('lab',other_lab)],201)
        self.other_project=made['created']['project']
        custodian=f.sql("SELECT custodian::text FROM dataset WHERE id='"+dataset+"'")
        made=self.mutation(f,'/v1/projects/'+self.other_project+'/datasets',owner_token,{'custodian':custodian},[('project',self.other_project)],201)
        self.admission=made['created']['dataset']
        for name in ['member','disabled','fixed','expired','positive','steward']:
            person=str(uuid.uuid4()); self.people[name]=person
            f.json('/v1/datasets/'+self.admission+'/grants',owner_token,{'recipient':person,'release':None,'actions':['ReadMetadata']},status=201)
            self.tokens[name]=credential(person,'pre-backup-'+name)
        member=self.mutation(f,'/v1/labs/'+lab+'/members',owner_token,{'principal':self.people['member'],'role':'Member'},[('lab',lab)],201)
        self.membership=member['created']['membership']
        self.mutation(f,'/v1/projects/'+project+'/participants',owner_token,
            {'principal':self.people['member'],'role':'Reader','membership':self.membership},[('project',project),('membership',self.membership)],201)
        impact=f.json('/v1/datasets/'+dataset+'/inheritance',owner_token)
        self.mutation(f,'/v1/datasets/'+dataset+'/inheritance',owner_token,{'policy':'ProjectMembers','previewDigest':impact['digest']},
            [('placement',dataset),('project',project)])
        correction=f.json('/v1/datasets/'+dataset+'/releases/'+self.first+'/corrections',owner_token,
            {'path':'dataset_description.json','text':'{"Name":"Second retained release","BIDSVersion":"1.10.0","DatasetType":"raw"}','reason':'Independent retained oracle'},status=201)
        self.second=f.json('/v1/candidates/'+correction['candidate']['id']+'/accept',owner_token,method='POST',key='second-retained')['ref']['release']
        for name in ['disabled','positive','fixed','expired']:
            expires=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=30)).isoformat() if name=='expired' else None
            self.grants[name]=f.json('/v1/datasets/'+dataset+'/grants',owner_token,{'recipient':self.people[name],
                'release':self.first if name=='fixed' else None,'actions':['ReadMetadata','ReadContent'],'expiresAt':expires},status=201)
        base='/v1/datasets/'+dataset+'/releases/'
        for name in ['member','disabled','positive','fixed','expired']:
            assert f.http(base+self.first,self.tokens[name]['token'])[0]==200
        assert f.http(base+self.second,self.tokens['fixed']['token'])[0]==404
        self.metadata={release:f.json(base+release+'/metadata?path='+self.metadata_path,owner_token) for release in [self.first,self.second]}
        accepted=f.rows("SELECT candidate_id::text,idempotency_key FROM acceptance_receipt WHERE release_id='"+self.first+"'")[0]
        self.candidate=accepted['candidate_id']; self.receipt_key=accepted['idempotency_key']
        self.receipt=f.json('/v1/candidates/'+self.candidate+'/accept',owner_token,method='POST',key=self.receipt_key)
        for release in [self.first,self.second]:
            destination=self.root/'source-oracle-exports'/release
            self.delivery(f,'export',self.people['positive'],release,destination)
            self.export_oracle[release]=self.inventory(destination)
        shutil.rmtree(self.root/'source-oracle-exports')
        (self.output/'export-oracle.json').write_text(json.dumps(self.export_oracle,indent=2)+'\n')
        self.checks.append('two-labs-projects-independent-fixed-and-expiring-shares-and-two-retained-releases')
    def changes(self,f,owner_token):
        self.mutation(f,'/v1/memberships/'+self.membership+'/end',owner_token,{},[('membership',self.membership),('lab',self.lab)])
        assert f.http('/v1/datasets/'+self.dataset+'/releases/'+self.first,self.tokens['member']['token'])[0]==404
        person=self.people['disabled']; generation=str(self.generation(f,'principal',person))
        f.cli('organization','disable-principal',self.owner,person,generation,'post-backup-disable')
        # Retain an independent read share for the former steward, while removing
        # project inheritance so its separate project role cannot mask the transfer.
        f.json('/v1/datasets/'+self.dataset+'/grants',owner_token,
            {'recipient':self.owner,'release':None,'actions':['ReadMetadata','ReadContent']},status=201)
        impact=f.json('/v1/datasets/'+self.dataset+'/inheritance',owner_token)
        self.mutation(f,'/v1/datasets/'+self.dataset+'/inheritance',owner_token,{'policy':'ExplicitOnly','previewDigest':impact['digest']},
            [('placement',self.dataset),('project',self.project)])
        placement=f.json('/v1/datasets/'+self.dataset+'/placement',owner_token)
        giving=next(x for x in placement['stewards'] if x['principal']==self.owner and x['status']=='active')
        moved=self.mutation(f,'/v1/stewardships/'+giving['id']+'/transfer',owner_token,{'principal':self.people['steward']},
            [('stewardship',giving['id']),('placement',self.dataset)],201)
        self.new_stewardship=moved['created']['stewardship']
        base='/v1/datasets/'+self.dataset+'/releases/'
        assert f.http(base+self.first,self.tokens['member']['token'])[0]==404
        assert f.http(base+self.first,self.tokens['disabled']['token'])[0]==401
        assert f.http('/v1/datasets/'+self.dataset+'/grants',owner_token)[0]==404
        assert f.http('/v1/datasets/'+self.dataset+'/grants',self.tokens['steward']['token'])[0]==200
        self.expected_policy={table:f.rows('SELECT * FROM organization_'+table) for table in ['lab','project','membership','participation','placement','stewardship','link','move']}
        self.restriction_head=f.sql('SELECT revision::text FROM organization_policy')
        acknowledgements=f.rows("SELECT e.revision,e.payload->>'kind' AS kind,a.certificate FROM organization_event e JOIN policy_acknowledgement a ON a.event_id=e.id ORDER BY e.revision")
        (self.output/'durable-acknowledgements.json').write_text(json.dumps(acknowledgements,indent=2)+'\n')
        self.checks.append('post-backup-share-member-disable-steward-transfer-and-token-revocation-durably-acknowledged')
    def isolate(self,f,bundle):
        self.store_context=self.backup.isolated_store(self.root,self.output)
        url,writer,reader,client,server,volumes=self.store_context.__enter__()
        store=self.backup.store
        key=store.verified(bundle)
        writer_file=self.root/'store-writer-token'; writer_file.write_text(writer); writer_file.chmod(0o600)
        self.run(['python3',self.backup.HERE/'backup-store.py','push','--url',url,'--token-file',writer_file,'--allow-insecure-test','--bundle',bundle])
        self.run([*client,'pull','--url','http://store:8080','--token-file','/reader/token','--allow-insecure-test','--bundle-id',key,'--output','/recovery/bundle'])
        downloaded=self.run(['docker','run','--rm','--user','10005:10005','-v',volumes['recovery']+':/recovery:ro',
            self.backup.PYTHON_IMAGE,'python','-c',"import sys,tarfile;t=tarfile.open(fileobj=sys.stdout.buffer,mode='w|');t.add('/recovery/bundle',arcname='bundle');t.close()"])
        recovery_root=self.root/'independent-recovery'; recovery_root.mkdir(mode=0o700); extract(downloaded,recovery_root)
        assert store.verified(recovery_root/'bundle')==key
        source_container=f.container; source_storage=f.storage; source_url=f.env['SANCTITY_JDBC_URL']
        original_inspect=json.loads(self.run(['docker','inspect',source_container]))[0]
        original_volumes={m.get('Name') for m in original_inspect['Mounts'] if m['Type']=='volume'}
        self.isolated_at=time.monotonic()
        f.stop(); self.run(['docker','rm','-fv',source_container])
        shutil.rmtree(source_storage); shutil.rmtree(self.root/'source'); shutil.rmtree(self.root/'package'); shutil.rmtree(self.root/'captures')
        assert not source_storage.exists() and not (self.root/'source').exists()
        assert subprocess.run(['docker','inspect',source_container],capture_output=True).returncode!=0
        target_password=uuid.uuid4().hex; self.secrets.extend([target_password,writer,reader])
        self.target=self.run(['docker','run','-d','--label','io.sanctity.purpose=source-isolated-recovery','-e','POSTGRES_USER=recovery_operator',
            '-e','POSTGRES_PASSWORD='+target_password,'-e','POSTGRES_DB=recovered','-p','127.0.0.1::5432',self.backup.compat.PG]).decode().strip()
        deadline=time.monotonic()+60
        while subprocess.run(['docker','exec',self.target,'pg_isready','-U','recovery_operator'],capture_output=True).returncode:
            assert time.monotonic()<deadline; time.sleep(.2)
        port=self.run(['docker','port',self.target,'5432/tcp']).decode().strip().rsplit(':',1)[1]
        env={k:v for k,v in f.env.items() if k in ['PATH','HOME','LANG','SANCTITY_HTTP_PORT']}
        env.update(SANCTITY_JDBC_URL='jdbc:postgresql://127.0.0.1:'+port+'/recovered',SANCTITY_DB_USER='recovery_operator',
            SANCTITY_DB_PASSWORD=target_password,SANCTITY_DB_SCHEMA='sanctity')
        for executable in ['pg_restore','psql']:
            path=recovery_root/executable
            path.write_text('#!/bin/sh\nexec docker exec -i -e PGUSER -e PGPASSWORD -e PGDATABASE '+self.target+' '+executable+' "$@"\n'); path.chmod(0o700)
            env['SANCTITY_'+executable.upper()]=str(path)
        inspect=json.loads(self.run(['docker','inspect',self.target]))[0]
        assert not any(m['Type']=='bind' for m in inspect['Mounts'])
        assert original_volumes.isdisjoint({m.get('Name') for m in inspect['Mounts'] if m['Type']=='volume'})
        (self.output/'source-isolation.json').write_text(json.dumps({'originalContainerRemoved':True,'originalStorageRemoved':True,
            'originalImportSourceRemoved':True,'originalPackageRemoved':True,'originalCaptureDirectoryRemoved':True,
            'targetContainer':self.target,'targetHasSourceMounts':False,'targetDatabaseRole':'recovery_operator',
            'originalDatabaseCredentialsReused':False,'originalDatabaseConnectionReused':False,'backupReadByUid':'10005:10005',
            'witnessUid':'10001:10001','witnessMounts':['/work','/trust','/opt/runtime'],
            'witnessHasSourceMounts':False,'witnessHasSourceDatabaseEnvironment':False,
            'sourceUrlDigest':hashlib.sha256(source_url.encode()).hexdigest(),'targetUrlDigest':hashlib.sha256(env['SANCTITY_JDBC_URL'].encode()).hexdigest()},indent=2)+'\n')
        self.checks.append('original-service-catalog-blobs-imports-and-capture-removed-before-fresh-target-restore')
        return self.target,env,recovery_root/'bundle'
    def before_runtime(self,f,credential):
        for name,person in self.people.items():
            if name=='disabled':
                path=self.root/'disabled-new-credential.json'
                f.cli('repository','issue-credential-once',str(uuid.uuid4()),person,'3600','disabled-must-refuse',str(path),fail=True)
                assert not path.exists()
            else: self.fresh[name]=credential(person,'post-recovery-'+name)
    def after_open(self,f,owner_token,sql,rows):
        base='/v1/datasets/'+self.dataset+'/releases/'
        for token in self.tokens.values(): assert f.http(base+self.first,token['token'])[0]==401
        for name in ['member','expired']: assert f.http(base+self.first,self.fresh[name]['token'])[0]==404
        for name in ['positive','steward','fixed']: assert f.http(base+self.first,self.fresh[name]['token'])[0]==200
        assert f.http(base+self.second,self.fresh['fixed']['token'])[0]==404
        assert f.http(base+self.first+'/file?path=README',self.fresh['fixed']['token'])[0]==200
        assert f.http(base+self.second+'/file?path=README',self.fresh['fixed']['token'])[0]==404
        for release in [self.first,self.second]:
            assert f.json(base+release+'/metadata?path='+self.metadata_path,self.fresh['positive']['token'])==self.metadata[release]
        assert f.http('/v1/datasets/'+self.dataset+'/grants',owner_token)[0]==404
        assert f.http('/v1/datasets/'+self.dataset+'/grants',self.fresh['steward']['token'])[0]==200
        made=f.json('/v1/datasets/'+self.dataset+'/grants',self.fresh['steward']['token'],
            {'recipient':self.owner,'release':self.first,'actions':['ReadMetadata']},status=201)
        f.json('/v1/datasets/'+self.dataset+'/grants/'+made['id'],self.fresh['steward']['token'],method='DELETE',status=204)
        retained={r['id'] for r in rows('SELECT id FROM dataset')}
        expected={table:[r for r in records if r['payload']['value'].get('dataset',next(iter(retained))) in retained]
            for table,records in self.expected_policy.items()}
        assert {table:rows('SELECT * FROM organization_'+table) for table in expected}==expected
        assert f.json('/v1/candidates/'+self.candidate+'/accept',owner_token,method='POST',key=self.receipt_key)==self.receipt
        for name in ['fixed','member']:
            assert f.http('/v1/candidates/'+self.candidate+'/accept',self.fresh[name]['token'],method='POST',key=self.receipt_key)[0]==404
        for release in [self.first,self.second]:
            destination=self.root/'recovered-exports'/release
            self.delivery(f,'export',self.people['positive'],release,destination)
            assert self.inventory(destination)==self.export_oracle[release]
        self.full_bytes_at=time.monotonic()
        details=self.root/'stream-revocation.json'
        details.write_text(json.dumps({'issuer':self.people['steward'],'grant':self.grants['positive']['id']})); details.chmod(0o600)
        stream=self.delivery(f,'stream',self.people['positive'],self.first,details)
        (self.output/'chunk-revocation.json').write_text(json.dumps(stream,indent=2)+'\n')
        self.checks.append('exact-full-exports-receipt-boundaries-and-next-64KiB-delivery-chunk-refused-after-revocation')
        for token in [*self.tokens.values(),*self.fresh.values()]: self.secrets.append(token['token'])
        for path in self.output.rglob('*'):
            if path.is_file():
                data=path.read_bytes()
                assert all(secret.encode() not in data for secret in self.secrets), 'secret canary in rehearsal evidence'
        elapsed=round(time.monotonic()-self.isolated_at,3)
        (self.output/'source-loss-outcomes.json').write_text(json.dumps({'zeroLostAcknowledgedRestrictions':True,
            'scientificDataLossAtBackupPoint':0,'postBackupScienceOutsideRecoveryPoint':'one release intentionally absent and tombstoned',
            'catalogVerifiedSeconds':round(self.catalog_verified_at-self.isolated_at,3),
            'catalogOpenedSeconds':round(self.catalog_opened_at-self.isolated_at,3),
            'apiAvailableSeconds':round(self.api_ready_at-self.isolated_at,3),
            'verifiedFullExportSeconds':round(self.full_bytes_at-self.isolated_at,3),
            'totalRehearsalAfterIsolationSeconds':elapsed,'observedPostBackupScientificLoss':{'emptyDatasets':1,'releases':1},'freshAuthorizedRead':True,'newStewardManageAccess':True,
            'formerStewardManageAccess':False,'removedMembershipRead':False,'disabledCredentialAdmission':False,
            'expiredShareRead':False,'fixedReleaseBoundaryRetained':True,'allOldTokensDenied':True},indent=2)+'\n')
        self.checks.append('source-isolated-current-policy-exact-metadata-fixed-release-positive-and-negative-oracles')
    def close(self):
        if self.target: self.run(['docker','rm','-fv',self.target],fail_ok=True)
        if self.store_context: self.store_context.__exit__(None,None,None)
        for volume in self.volumes.values(): self.run(['docker','volume','rm',volume],fail_ok=True)
        self.run(['docker','image','rm',self.image],fail_ok=True)
