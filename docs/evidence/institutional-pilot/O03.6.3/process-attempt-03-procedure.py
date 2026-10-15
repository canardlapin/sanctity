#!/usr/bin/env python3
"""Kill isolated primary/witness JVMs at real SQL/fsync boundaries. Synthetic disposable state only."""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
import zipfile

PG = 'postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24'
JRE = 'eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c'
MAIN = 'sanctity.postgres.PolicyProcessQualification'
WITNESS = 'sanctity.policy.PolicyProcessWitness'
PASSWORD = 'disposable_policy_qualification_only'
UIDS = {'witness':10001,'primary':10002,'operator':10003}

def sha(data): return hashlib.sha256(data).hexdigest()
def encoded(value): return json.dumps(value,sort_keys=True,separators=(',',':')).encode()
def run(args, *, data=None, check=True, timeout=90):
    p=subprocess.run([str(x) for x in args],input=data,capture_output=True,timeout=timeout)
    if check and p.returncode:
        # Fixture input/output can contain synthetic credentials; keep failures redacted.
        detail=p.stderr.decode(errors='replace').replace(PASSWORD,'[fixture secret]').replace('disposable_runtime_only','[fixture secret]')
        raise RuntimeError('qualification subprocess failed: '+str(args[0])+' (exit '+str(p.returncode)+'): '+detail[:1200])
    return p

def docker(*args, **kwargs): return run(['docker',*args],**kwargs)

class Fixture:
    def __init__(self, artifact, harness, output, root):
        self.artifact,self.harness,self.output,self.root=artifact,harness,output,root
        self.prefix='sanctity-policy-'+uuid.uuid4().hex[:12]
        self.network=self.prefix+'-net'; self.containers={}; self.volumes=[]; self.children=[]
        self.database='fixture';self.index=0;self.results=[];self.signals={}
        self.image=self.prefix+':qualification'
    def __enter__(self):
        try:
            self.build();self.start();self.prepare();return self
        except BaseException:
            self.__exit__(None,None,None);raise
    def __exit__(self,*args):
        for p,log in self.children:
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=3)
                except subprocess.TimeoutExpired:p.kill();p.wait()
            log.close()
        for c in reversed(list(self.containers.values())):docker('rm','-fv',c,check=False,timeout=30)
        for v in self.volumes:docker('volume','rm',v,check=False,timeout=30)
        docker('network','rm',self.network,check=False,timeout=30)
    def build(self):
        unpack=self.root/'runtime';unpack.mkdir()
        with zipfile.ZipFile(self.artifact) as z:
            for n in z.namelist():assert not n.startswith('/') and '..' not in Path(n).parts
            z.extractall(unpack)
        self.runtime=next(unpack.iterdir())
        shutil.copyfile(self.harness,self.runtime/'qualification.jar')
        for p in self.runtime.rglob('*'):p.chmod(0o755 if p.is_dir() or p.name=='sanctity' else 0o644)
        self.runtime.chmod(0o755)
        dockerfile='FROM '+JRE+'\nRUN groupadd -g 10001 witness && useradd -u 10001 -g witness witness && groupadd -g 10002 primary && useradd -u 10002 -g primary primary && groupadd -g 10003 policy_operator && useradd -u 10003 -g policy_operator operator\n'
        build=self.root/'image';build.mkdir();(build/'Dockerfile').write_text(dockerfile)
        (self.output/'Dockerfile').write_text(dockerfile)
        p=docker('build','--label','io.sanctity.purpose=policy-process-qualification','-t',self.image,build,timeout=180,check=False)
        (self.output/'image-build.log').write_bytes(p.stdout+p.stderr)
        assert p.returncode==0,'fixture image build failed; see image-build.log'
        self.image_id=docker('image','inspect',self.image,'--format','{{.Id}}').stdout.decode().strip()
    def start(self):
        docker('network','create','--internal','--label','io.sanctity.purpose=policy-process-qualification',self.network)
        pg=self.prefix+'-pg'
        self.containers['pg']=pg
        docker('run','-d','--name',pg,'--label','io.sanctity.purpose=policy-process-qualification',
            '--network',self.network,'--network-alias','pg','-e','POSTGRES_USER=fixture',
            '-e','POSTGRES_PASSWORD='+PASSWORD,'-e','POSTGRES_DB=fixture',PG)
        deadline=time.monotonic()+60
        while docker('exec',pg,'pg_isready','-h','127.0.0.1','-U','fixture','-d','fixture',check=False).returncode:
            assert time.monotonic()<deadline;time.sleep(.1)
        self.sql("CREATE ROLE old_runtime LOGIN NOINHERIT PASSWORD 'disposable_runtime_only'; CREATE ROLE new_runtime LOGIN NOINHERIT PASSWORD 'disposable_runtime_only'; REVOKE CREATE,TEMPORARY ON DATABASE fixture FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC;",schema=False)
        for role in UIDS:
            name=self.prefix+'-'+role
            self.containers[role]=name
            mounts=[]
            for location in ['work','trust']:
                volume=self.prefix+'-'+role+'-'+location;self.volumes.append(volume)
                docker('volume','create','--label','io.sanctity.purpose=policy-process-qualification',volume)
                mounts+=['--mount','type=volume,source='+volume+',target=/'+location]
            network=self.network if role=='witness' else 'container:'+self.containers['witness']
            docker('run','-d','--name',name,'--label','io.sanctity.purpose=policy-process-qualification',
                '--network',network,'--user',role,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                '--tmpfs','/tmp:rw,nosuid,nodev,size=67108864','--mount','type=bind,source='+str(self.runtime)+',target=/opt/runtime,readonly',
                *mounts,self.image,'sleep','infinity')
            # Provision only the newly created synthetic volumes using a short-lived root helper.
            docker('run','--rm','--network','none','--user','root',*mounts,self.image,'sh','-c',
                'chown -R '+str(UIDS[role])+':'+str(UIDS[role])+' /work /trust && chmod 700 /work /trust')
    def sql(self,sql,db=None,schema=True):
        data=(('SET search_path TO sanctity,pg_catalog;\n' if schema else '')+sql).encode()
        return docker('exec','-i',self.containers['pg'],'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','fixture','-d',db or self.database,data=data).stdout.decode().strip()
    def write(self,role,path,data):
        assert path.startswith(('/work/','/trust/')) and all(c.isalnum() or c in '/._-' for c in path)
        if isinstance(data,str):data=data.encode()
        docker('exec','-i',self.containers[role],'sh','-c','umask 077; cat > '+path,data=data)
    def read(self,role,path,optional=False):
        p=docker('exec',self.containers[role],'cat',path,check=not optional)
        return p.stdout if p.returncode==0 else None
    def shell(self,role,command):return docker('exec',self.containers[role],'sh','-c',command).stdout
    def command(self,role,main,args,sqlrole=None):
        env=[]
        if role!='witness':
            user=sqlrole or ('new_runtime' if role=='primary' else 'fixture')
            password='disposable_runtime_only' if user in ['new_runtime','old_runtime'] else PASSWORD
            for k,v in {'SANCTITY_JDBC_URL':'jdbc:postgresql://pg:5432/'+self.database,'SANCTITY_DB_USER':user,
                'SANCTITY_DB_PASSWORD':password,'SANCTITY_DB_SCHEMA':'sanctity'}.items():env+=['-e',k+'='+v]
        return ['docker','exec',*env,self.containers[role],'java','-XX:ActiveProcessorCount=2','-Xmx256m','-cp',
                '/opt/runtime/lib/*:/opt/runtime/qualification.jar',main,*args]
    def helper(self,action,*args,role='operator',ok=True,sqlrole=None):
        p=run(self.command(role,MAIN,[action,*args],sqlrole),check=False,timeout=45)
        lines=p.stdout.decode().splitlines()
        try:result=json.loads(lines[-1])
        except (IndexError,json.JSONDecodeError):
            (self.output/(self.current+'-unexpected.log')).write_bytes(p.stdout+p.stderr)
            raise AssertionError('no structured qualification result: '+action)
        assert (p.returncode==0 and result['ok']) if ok else (p.returncode!=0 and not result['ok']),(action,result)
        return result['result'] if ok else result
    def spawn(self,role,main,args,label):
        self.shell(role,'rm -f /work/boundary.json /work/pid /work/ready')
        path=self.output/(self.current+'-'+label+'.log')
        suffix=0
        while path.exists():
            suffix+=1;path=self.output/(self.current+'-'+label+'-'+str(suffix)+'.log')
        log=path.open('xb')
        p=subprocess.Popen(self.command(role,main,args),stdout=log,stderr=subprocess.STDOUT)
        self.children.append((p,log));return p
    def wait_file(self,role,path,process,timeout=15):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            value=self.read(role,path,True)
            if value:return value
            assert process.poll() is None,('process exited before boundary',role,self.current)
            time.sleep(.03)
        raise AssertionError('boundary timeout: '+self.current+' '+path)
    def stop(self,role,process):
        pid=self.read(role,'/work/pid',True)
        if pid and process.poll() is None:
            assert pid.decode().strip().isdigit()
            docker('exec',self.containers[role],'kill','-9',pid.decode().strip(),check=False)
        process.wait(timeout=15)
    def witness_start(self):
        p=self.spawn('witness',WITNESS,['serve'],'witness')
        self.wait_file('witness','/work/ready',p);return p
    def witness_stop(self):
        if getattr(self,'witness',None):self.stop('witness',self.witness);self.witness=None
    def clone(self,name,template):
        self.sql('CREATE DATABASE '+name+' TEMPLATE '+template,db='postgres',schema=False)
        self.sql('REVOKE CREATE,TEMPORARY ON DATABASE '+name+' FROM PUBLIC',db='postgres',schema=False)
    def reset(self,label,enrolled=True):
        self.witness_stop();self.index+=1;self.current=label;self.database='case'+str(self.index)
        self.clone(self.database,'enrolled' if enrolled else 'before_enroll')
        self.shell('witness','rm -rf /work/journal; cp -a /work/'+('enrolled-journal' if enrolled else 'empty-journal')+' /work/journal; rm -f /work/control.json /work/boundary.json')
        self.witness=self.witness_start()
        self.shell('primary','rm -f /work/watch.jsonl /work/boundary.json')
        self.started=time.monotonic()
    def scientific_identity(self):
        tables=['manifest','blob','manifest_entry','interpretation','metadata_artifact','metadata_field','assessment','release','acceptance_receipt']
        values={t:self.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+t+" t") for t in tables}
        return sha(encoded(values))
    def record(self,checks,**details):
        assert self.scientific_identity()==self.scientific_before,'scientific history changed'
        prefix=self.sql("SELECT jsonb_agg(to_jsonb(t) ORDER BY revision)::text FROM organization_event t WHERE revision<="+str(self.baseline['revision']))
        assert prefix==self.history_before,'pre-enrollment event bytes changed'
        row={'case':self.current,'status':'passed','checks':checks,'elapsedSeconds':round(time.monotonic()-self.started,3),**details}
        self.results.append(row)
        (self.output/'outcomes.json').write_text(json.dumps(self.results,indent=2)+'\n')
        print('passed',self.current,flush=True)
    def journal(self):return self.helper('records')
    def state(self):
        return json.loads(self.sql("SELECT json_build_object('revision',revision,'head',applied_hash,'epoch',writer_epoch,'mode',durability_mode,'events',(SELECT count(*) FROM organization_event),'acks',(SELECT count(*) FROM policy_acknowledgement),'revoked',(SELECT revoked_at IS NOT NULL FROM access_grant WHERE id='"+self.fixture['grant']+"')) FROM organization_policy"))
    def prepare(self):
        self.current='setup';self.started=time.monotonic()
        self.fixture=self.helper('setup')
        for name in ['fixture.json','secrets.json']:self.write('primary','/work/'+name,self.read('operator','/work/'+name))
        material=self.root/'material';material.mkdir(mode=0o700)
        keytool=str(Path(os.environ['JAVA_HOME'])/'bin/keytool') if os.environ.get('JAVA_HOME') else shutil.which('keytool')
        assert keytool
        fingerprints={}
        for who in ['server','writer','recovery']:
            run([keytool,'-genkeypair','-alias',who,'-keyalg','EC','-groupname','secp256r1','-dname','CN='+who,'-validity','2','-ext','SAN=dns:localhost',
                '-keystore',material/(who+'.p12'),'-storetype','PKCS12','-storepass',PASSWORD,'-keypass',PASSWORD,'-noprompt'])
            run([keytool,'-exportcert','-alias',who,'-keystore',material/(who+'.p12'),'-storepass',PASSWORD,'-file',material/(who+'.der')])
            run([keytool,'-importcert','-alias',who,'-file',material/(who+'.der'),'-keystore',material/'anchors.p12','-storetype','PKCS12','-storepass',PASSWORD,'-noprompt'])
            fingerprints[who]=sha((material/(who+'.der')).read_bytes())
        public=bytes.fromhex('302a300506032b6570032100'+'d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a')
        private=bytes.fromhex('302e020100300506032b657004220420'+'9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60')
        for role in UIDS:
            self.write(role,'/trust/password',PASSWORD)
            self.write(role,'/trust/anchors.p12',(material/'anchors.p12').read_bytes())
            self.write(role,'/trust/signing-public.der',public)
            identities={'witness':['server'],'primary':['writer'],'operator':['writer','recovery']}[role]
            for identity in identities:self.write(role,'/trust/'+identity+'.p12',(material/(identity+'.p12')).read_bytes())
            for identity in identities:
                if identity=='server':continue
                value={'installation':self.fixture['installation'],'endpoint':'https://localhost:9443/v1/policy',
                    'identityStore':'/trust/'+identity+'.p12','identityPasswordFile':'/trust/password','trustStore':'/trust/anchors.p12',
                    'trustPasswordFile':'/trust/password','signingPublic':'/trust/signing-public.der'}
                self.write(role,'/trust/'+identity+'.json',encoded(value))
        self.write('witness','/trust/signing-private.der',private)
        config={'installation':self.fixture['installation'],'store':'/work/journal','identityStore':'/trust/server.p12',
            'identityPasswordFile':'/trust/password','trustStore':'/trust/anchors.p12','trustPasswordFile':'/trust/password',
            'signingPrivate':'/trust/signing-private.der','signingPublic':'/trust/signing-public.der',
            'writerFingerprint':fingerprints['writer'],'recoveryFingerprint':fingerprints['recovery'],'port':'9443'}
        self.write('witness','/trust/witness.json',encoded(config))
        self.clone('before_enroll','fixture')
        run(self.command('witness',WITNESS,['init']))
        self.shell('witness','cp -a /work/journal /work/empty-journal')
        self.witness=self.witness_start()
        self.history_before=self.sql("SELECT jsonb_agg(to_jsonb(t) ORDER BY revision)::text FROM organization_event t")
        self.scientific_before=self.scientific_identity()
        self.helper('enroll');self.helper('runtime-profile')
        assert self.sql("SELECT jsonb_agg(to_jsonb(t) ORDER BY revision)::text FROM organization_event t")==self.history_before
        assert self.sql("SELECT has_table_privilege('old_runtime','organization_policy','SELECT')")=='f'
        self.helper('readiness',role='primary')
        assert all(self.helper('restrictions',role='primary').values())
        checkpoint_text=self.sql("SELECT command->>'checkpoint' FROM policy_enrollment")
        checkpoint=base64.urlsafe_b64decode(checkpoint_text+'='*(-len(checkpoint_text)%4))
        data=json.loads(checkpoint);tables=data['tables']
        for table in ['principal','access_grant','api_credential','import_operation','outbox_delivery','release','organization_event']:
            assert tables[table],table
        assert any(not r['active'] for r in tables['principal'])
        assert any(r['revoked_at'] for r in tables['access_grant'])
        assert any(r['revoked_at'] for r in tables['api_credential'])
        assert any(r['leased'] for r in tables['import_operation']) and any(r['leased'] for r in tables['outbox_delivery'])
        for s in json.loads(self.read('operator','/work/secrets.json')).values():assert s.encode() not in checkpoint
        assert b'token_digest' not in checkpoint and b'lease_token' not in checkpoint
        self.baseline=self.state();self.checkpoint_sha=sha(checkpoint)
        self.witness_stop();self.shell('witness','cp -a /work/journal /work/enrolled-journal')
        self.clone('enrolled','fixture')
        # Actual OS permission checks with the other store mounted, not merely absent.
        for role,foreign in [('primary','witness'),('witness','primary')]:
            docker('run','--rm','--network','none','--user',role,'--cap-drop','ALL','--security-opt','no-new-privileges',
                '--mount','type=volume,source='+self.prefix+'-'+foreign+'-work,target=/foreign',
                '--mount','type=volume,source='+self.prefix+'-'+foreign+'-trust,target=/foreign-trust',self.image,
                'sh','-c','test ! -r /foreign && test ! -w /foreign && test ! -r /foreign-trust && test ! -w /foreign-trust')
        assert self.read('primary','/trust/recovery.p12',True) is None
        assert self.read('primary','/trust/signing-private.der',True) is None
        assert self.read('witness','/trust/writer.p12',True) is None
        self.configuration={'installation':self.fixture['installation'],'baseImage':JRE,'fixtureImageId':self.image_id,
            'postgresImage':PG,'uids':UIDS,'fileMode':'0600','directoryMode':'0700','readOnlyRootFilesystem':True,
            'separateNamedVolumes':True,'crossMountedStoreAccess':'read/write denied in both directions',
            'writerHasRecoveryOrSigningPrivateKey':False,'certificateFingerprints':fingerprints,
            'witnessConfigurationSha256':sha(encoded(config)),'signingPublicSha256':sha(public),'checkpointSha256':self.checkpoint_sha,
            'scientificHistorySha256':self.scientific_before,'signingFixture':'Known RFC8032 vector 1; synthetic only',
            'java':docker('exec',self.containers['witness'],'java','-version').stderr.decode().strip(),
            'postgres':json.loads(self.sql("SELECT json_build_object('version',version(),'fsync',current_setting('fsync'),'synchronousCommit',current_setting('synchronous_commit'),'fullPageWrites',current_setting('full_page_writes'))"))}
        self.record(['complete restrictive enrollment','old history byte identity','revoked grant and credentials remain denied','leased intake/delivery state included without secrets','retired old SQL role','fresh least-privilege role','separate OS users and real cross-store permission denial'])

    def primary_boundary(self,stage):
        self.reset('primary-'+stage)
        p=self.spawn('primary',MAIN,['revoke',stage],'primary')
        self.wait_file('primary','/work/boundary.json',p)
        if stage.startswith('lost-'):
            p.wait(timeout=15)
            response=json.loads((self.output/(self.current+'-primary.log')).read_text().splitlines()[-1])
            assert not response['ok'] and response['outcome']==('unknown' if stage=='lost-local-response' else 'pending')
        else:self.stop('primary',p)
        state=self.state();records=self.journal()
        committed=stage not in ['before-prepare','before-local-commit']
        assert state['revoked']==committed
        assert state['events']==self.baseline['events']+int(committed)
        assert not any('"status":"durable"' in (self.output/(self.current+'-primary.log')).read_text() for _ in [0])
        if stage=='before-prepare':
            assert len(records)==1
            self.helper('readiness',role='primary');self.helper('revoke',role='primary')
        elif not committed:
            assert [r['kind'] for r in records]==['ENROLL','PREPARE']
            self.helper('resolve',ok=False);self.helper('grant',role='primary',ok=False)
            assert self.state()==state
        else:
            assert not self.helper('check',role='primary')['allowed']
            self.helper('resolve');self.helper('revoke',role='primary')
            assert self.state()['events']==state['events'] and self.state()['acks']==1
            final=self.journal();assert [r['kind'] for r in final]==['ENROLL','PREPARE','COMMIT']
            assert final[1]['command']==final[2]['command']
        self.record(['kill or lost response at exact JDBC boundary','no false durable response','atomic local restriction/event','restart exact outcome or conservative refusal','no duplicate event'],localCommitted=committed,journalKinds=[r['kind'] for r in records])

    def witness_boundary(self,kind,stage):
        self.reset('witness-'+kind.lower()+'-'+stage,enrolled=kind!='ENROLL')
        self.write('witness','/work/control.json',encoded({'kind':kind,'stage':stage}))
        if kind=='ABORT':
            self.sql("CREATE FUNCTION reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'qualification rejection'; END $$; CREATE TRIGGER reject_event BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION reject_event()")
        action='enroll' if kind=='ENROLL' else 'barrier' if kind=='RECOVERY_BARRIER' else 'revoke'
        role='operator' if kind in ['ENROLL','RECOVERY_BARRIER'] else 'primary'
        p=self.spawn(role,MAIN,[action],'request')
        self.wait_file('witness','/work/boundary.json',self.witness)
        self.witness_stop();p.wait(timeout=15)
        response=json.loads((self.output/(self.current+'-request.log')).read_text().splitlines()[-1])
        assert not response['ok']
        state=self.state()
        if kind=='ENROLL':assert state['mode']=='enrolling' and self.sql('SELECT recovery_required FROM repository_state')=='t'
        if kind=='PREPARE':assert not state['revoked']
        if kind=='COMMIT':assert state['revoked'] and state['acks']==0
        if kind=='ABORT':assert not state['revoked'] and state['events']==self.baseline['events']
        self.shell('witness','rm /work/control.json')
        self.witness=self.witness_start()
        if kind=='ENROLL':
            self.helper('enroll');self.helper('runtime-profile')
            assert self.sql("SELECT jsonb_agg(to_jsonb(t) ORDER BY revision)::text FROM organization_event t")==self.history_before
            assert all(self.helper('restrictions',role='primary').values())
        elif kind=='PREPARE':
            self.helper('resolve',ok=False);self.helper('grant',role='primary',ok=False)
        elif kind=='COMMIT':
            assert not self.helper('check',role='primary')['allowed']
            self.helper('resolve');self.helper('revoke',role='primary');assert self.state()['acks']==1
        elif kind=='RECOVERY_BARRIER':
            self.helper('readiness',role='primary',ok=False)
            assert self.helper('current')['barrier'] is not None
            assert self.helper('retry-barrier')['exact']
        elif kind=='ABORT':
            self.sql('DROP TRIGGER reject_event ON organization_event; DROP FUNCTION reject_event()')
            self.helper('readiness',role='primary')
        records=self.journal();expected=['ENROLL'] if kind=='ENROLL' else ['ENROLL','RECOVERY_BARRIER'] if kind=='RECOVERY_BARRIER' else ['ENROLL','PREPARE']+([] if kind=='PREPARE' else [kind])
        assert [r['kind'] for r in records]==expected
        self.record(['separate witness SIGKILL after forced '+stage,'response cannot report success','verified restart retains exact durable suffix','exact resumption or closed unresolved absence'],journalKinds=expected)

    def pending_and_loss(self):
        self.reset('pending-restriction-no-overtaking')
        p=self.spawn('primary',MAIN,['revoke','after-local-commit'],'primary')
        self.wait_file('primary','/work/boundary.json',p);self.stop('primary',p)
        assert not self.helper('check',role='primary')['allowed']
        before=self.state();self.witness_stop()
        self.helper('grant',role='primary',ok=False);assert self.state()==before
        self.witness=self.witness_start();self.helper('resolve');self.helper('grant',role='primary')
        records=self.journal();assert [r['kind'] for r in records]==['ENROLL','PREPARE','COMMIT','PREPARE','COMMIT']
        self.record(['locally committed revocation denies before acknowledgement','outage prevents overtaking','successor PREPARE follows predecessor COMMIT'])
        self.reset('lost-primary-unresolved-prepare')
        p=self.spawn('primary',MAIN,['revoke','before-local-commit'],'primary')
        self.wait_file('primary','/work/boundary.json',p);self.stop('primary',p)
        # Destroy this case's actual primary database. Recovery uses only witness trust.
        self.sql('DROP DATABASE '+self.database,db='postgres',schema=False)
        barrier=self.helper('barrier')['current']
        assert barrier['pending'] is not None and barrier['barrier'] is not None
        self.clone(self.database,'enrolled')
        self.helper('readiness',role='primary',ok=False);self.helper('resolve',ok=False);self.helper('enroll',ok=False)
        assert [r['kind'] for r in self.journal()]==['ENROLL','PREPARE','RECOVERY_BARRIER']
        self.record(['actual primary database destroyed','fresh signed barrier obtained without primary','unresolved intent retained','old backup cannot authorize ABORT or reopen'])
        self.reset('acknowledged-primary-loss')
        self.helper('revoke',role='primary')
        acknowledged=self.state();self.sql('DROP DATABASE '+self.database,db='postgres',schema=False)
        current=self.helper('current');records=self.journal()
        assert current['appliedHash']==acknowledged['head'] and records[-1]['kind']=='COMMIT'
        self.witness_stop();self.witness=self.witness_start()
        assert self.helper('current')==current
        self.clone(self.database,'enrolled');self.helper('readiness',role='primary',ok=False)
        self.record(['acknowledged policy survives actual primary database deletion','witness restart retains exact head','restored stale catalog refused'],acknowledgedHead=acknowledged['head'])

    def fencing(self):
        self.reset('barrier-running-service-and-permit-drain')
        watch=self.spawn('primary',MAIN,['watch'],'watch')
        self.wait_file('primary','/work/boundary.json',watch)
        # Keep watch PID because the separate held transaction uses the same signal directory.
        watch_pid=self.read('primary','/work/pid').decode().strip()
        hold=self.spawn('primary',MAIN,['hold'],'hold')
        mark=json.loads(self.wait_file('primary','/work/boundary.json',hold))
        admitted=datetime.datetime.fromisoformat(mark['at'].replace('Z','+00:00'))
        old=self.state();before=self.sql('SELECT sum(attempts) FROM outbox_delivery')
        barrier=self.helper('barrier')['current']
        barrier_return=datetime.datetime.now(datetime.timezone.utc)
        assert int(barrier['epoch'])==old['epoch']+1
        deadline=time.monotonic()+7
        while self.sql("SELECT count(*) FROM pg_stat_activity WHERE pid="+mark['backend']+" AND xact_start IS NOT NULL")!='0':
            assert time.monotonic()<deadline
            time.sleep(.03)
        drained=datetime.datetime.now(datetime.timezone.utc)
        drain_seconds=(drained-admitted).total_seconds()
        assert drain_seconds <= 5.25,drain_seconds
        hold.wait(timeout=12)
        held=json.loads((self.output/(self.current+'-hold.log')).read_text().splitlines()[-1]);assert not held['ok']
        assert self.sql('SELECT sum(attempts) FROM outbox_delivery')==before
        assert self.state()['epoch']==old['epoch']
        time.sleep(.3)
        observations=[json.loads(x) for x in self.read('primary','/work/watch.jsonl').decode().splitlines()]
        denied=[x for x in observations if not x['admitted']]
        assert denied
        first=datetime.datetime.fromisoformat(denied[0]['at'].replace('Z','+00:00'))
        assert (first-barrier_return).total_seconds()<5
        assert not any(x['admitted'] for x in observations if datetime.datetime.fromisoformat(x['at'].replace('Z','+00:00'))>barrier_return)
        # Observe the held backend specifically: the live watcher keeps opening
        # fresh transactions to test denied admission, so a database-wide count
        # can legitimately see one of those brief transactions between guards.
        assert self.sql("SELECT count(*) FROM pg_stat_activity WHERE pid="+mark['backend']+" AND xact_start IS NOT NULL")=='0'
        (self.output/(self.current+'-observations.json')).write_text(json.dumps({
            'heldBackend':mark['backend'],'drainSeconds':drain_seconds,'admissions':observations},indent=2)+'\n')
        docker('exec',self.containers['primary'],'kill','-9',watch_pid);watch.wait(timeout=10)
        deadline=time.monotonic()+3
        while self.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='new_runtime' AND xact_start IS NOT NULL")!='0':
            assert time.monotonic()<deadline,'transactions remain after watcher termination'
            time.sleep(.03)
        self.helper('readiness',role='primary',ok=False)
        self.record(['running service denied after fresh barrier without local epoch change','admitted SQL transaction aborts and rolls back','restart refuses old writer'],admittedAt=admitted.isoformat(),barrierReturnedAt=barrier_return.isoformat(),firstDeniedAt=first.isoformat(),drainSeconds=drain_seconds,samplingToleranceSeconds=.25,drainObservation='held server transaction disappears within five-second permit plus polling tolerance')

    def packaged_fencing(self):
        self.reset('packaged-http-barrier-fencing')
        self.shell('primary','mkdir -p /work/blobs; chmod 700 /work/blobs')
        token=json.loads(self.read('operator','/work/secrets.json'))['active']
        self.write('operator','/work/http-client.conf','header = "Authorization: Bearer '+token+'"\n')
        name=self.prefix+'-api';self.containers['api']=name
        environment={'SANCTITY_JDBC_URL':'jdbc:postgresql://pg:5432/'+self.database,'SANCTITY_DB_USER':'new_runtime',
            'SANCTITY_DB_PASSWORD':'disposable_runtime_only','SANCTITY_DB_SCHEMA':'sanctity','SANCTITY_STORAGE':'/work/blobs',
            'SANCTITY_HTTP_PORT':'18881','SANCTITY_POLICY_CONFIG':'/trust/writer.json'}
        env=[item for k,v in environment.items() for item in ['-e',k+'='+v]]
        command=['run','-d','--name',name,'--label','io.sanctity.purpose=policy-process-qualification',
            '--network','container:'+self.containers['witness'],'--user','primary','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges','--tmpfs','/tmp:rw,nosuid,nodev,size=67108864',
            '--tmpfs','/home/primary:rw,nosuid,nodev,uid=10002,gid=10002,mode=700,size=1048576',
            '--mount','type=bind,source='+str(self.runtime)+',target=/opt/runtime,readonly',
            '--mount','type=volume,source='+self.prefix+'-primary-work,target=/work',
            '--mount','type=volume,source='+self.prefix+'-primary-trust,target=/trust',*env,self.image,
            'java','-XX:ActiveProcessorCount=2','-Xmx256m','-cp','/opt/runtime/lib/*','sanctity.app.ServiceMain','serve']
        docker(*command)
        def status():
            p=docker('exec',self.containers['operator'],'curl','--silent','--show-error','--max-time','4',
                '--output','/work/http-body','--write-out','%{http_code}','--config','/work/http-client.conf',
                'http://127.0.0.1:18881/v1/me',check=False)
            return int(p.stdout or b'0') if p.returncode==0 else 0
        try:
            deadline=time.monotonic()+25
            while status()!=200:
                assert docker('inspect',name,'--format','{{.State.Running}}').stdout.strip()==b'true','packaged API stopped'
                assert time.monotonic()<deadline,'packaged API readiness timeout'
                time.sleep(.1)
            before=self.state();self.helper('barrier')
            after=status();assert after==503,after
            assert self.state()['epoch']==before['epoch']
            body=self.read('operator','/work/http-body');assert token.encode() not in body
            assert json.loads(body)['error']=='authentication_unavailable'
            docker('kill','--signal','KILL',name)
            docker('start',name)
            stopped=docker('wait',name,timeout=20).stdout.strip();assert stopped!=b'0'
            assert status()==0
            self.record(['real packaged API accepts valid bearer before barrier','same running API returns 503 after barrier',
                'local epoch unchanged','restarted packaged API refuses stale writer'],beforeStatus=200,afterStatus=after,
                classpath='artifact lib jars only; no qualification harness on service classpath')
        finally:
            log=docker('logs',name,check=False)
            (self.output/(self.current+'-api.log')).write_bytes(log.stdout+log.stderr)
            docker('rm','-fv',name,check=False)

    def corruption(self):
        for alteration in ['gap','reorder','payload','capability','foreign']:
            self.reset('store-'+alteration);self.helper('revoke',role='primary');self.witness_stop()
            run(self.command('witness',WITNESS,['alter',alteration]))
            p=run(self.command('witness',WITNESS,['inspect']),check=False)
            assert p.returncode!=0
            self.helper('readiness',role='primary',ok=False)
            self.record(['altered independent store refuses verified restart','primary admission remains closed'],alteration=alteration)
        self.reset('empty-witness-refusal');self.witness_stop()
        self.shell('witness','rm -rf /work/journal; cp -a /work/empty-journal /work/journal')
        self.witness=self.witness_start();self.helper('readiness',role='primary',ok=False);self.helper('enroll',ok=False)
        assert not self.helper('current')['enrolled']
        self.record(['empty witness cannot establish current authority','completed primary cannot re-enroll an empty witness'])
        self.reset('altered-client-trust')
        original=self.read('primary','/trust/signing-public.der')
        # Another valid Ed25519 public key (RFC8032 vector 2), not an invalid file parser test.
        other=bytes.fromhex('302a300506032b6570032100'+'3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c')
        self.write('primary','/trust/signing-public.der',other)
        self.helper('readiness',role='primary',ok=False)
        self.write('primary','/trust/signing-public.der',original)
        self.helper('readiness',role='primary')
        config=json.loads(self.read('primary','/trust/writer.json'));good=encoded(config)
        config['installation']=str(uuid.uuid4());self.write('primary','/trust/writer.json',encoded(config))
        self.helper('readiness',role='primary',ok=False)
        self.write('primary','/trust/writer.json',good)
        self.record(['valid alternate signing pin rejected','foreign installation trust rejected','original protected trust succeeds'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact',type=Path,required=True);parser.add_argument('--harness',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--smoke-only',action='store_true')
    parser.add_argument('--packaged-only',action='store_true')
    args=parser.parse_args();output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    artifact=args.artifact.resolve();harness=args.harness.resolve()
    with tempfile.TemporaryDirectory(prefix='sanctity-policy-process-') as temporary:
        with Fixture(artifact,harness,output,Path(temporary).resolve()) as f:
            if args.packaged_only:f.packaged_fencing()
            elif not args.smoke_only:
                for stage in ['before-prepare','before-local-commit','after-local-commit','lost-local-response','before-receipt-insert','before-receipt-commit','after-receipt-commit','lost-receipt-response']:f.primary_boundary(stage)
                for kind in ['ENROLL','PREPARE','COMMIT','ABORT','RECOVERY_BARRIER']:
                    for stage in ['record','head']:f.witness_boundary(kind,stage)
                f.reset('exact-retry-and-adversarial-wire');f.helper('revoke',role='primary')
                adversarial=f.helper('adversarial');assert adversarial['exactDuplicateTerminal']
                f.record(['separate writer exact terminal replay with unchanged heads',*adversarial['negativeCases']])
                f.pending_and_loss();f.fencing();f.corruption();f.packaged_fencing()
            receipt={'schemaVersion':1,'status':'passed','artifactSha256':sha(artifact.read_bytes()),'harnessSha256':sha(harness.read_bytes()),
                'cases':f.results,'configuration':f.configuration,'scope':'Real isolated Java processes, PostgreSQL and TLS witness; synthetic primary deletion and exact durable boundaries.',
                'limitations':['Same Docker host and physical storage; not host/disk-loss or institutional qualification.','Test-only JDBC and after-force pause controls are excluded from the service artifact.','No organization replay or barrier release; restored catalogs stay closed.']}
            (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
            print(json.dumps({'status':'passed','cases':len(f.results),'artifactSha256':receipt['artifactSha256']}),flush=True)
if __name__=='__main__':main()
