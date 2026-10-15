"""Observe the production finalizer's deadline using a real stalled SQL insert."""
from pathlib import Path
import datetime,importlib.util,json,tempfile,time

out=Path(__file__).resolve().parent
root=out.parents[3]
source=out/'process-attempt-03-procedure.py'
spec=importlib.util.spec_from_file_location('process_fixture',source)
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
artifact=root/'target/service-release-o0363-20261015/sanctity-0.1.0-SNAPSHOT.zip'
harness=out/'qualification-harness.jar'
output=out/'finalization-attempt-01';output.mkdir(exist_ok=False)
with tempfile.TemporaryDirectory(prefix='sanctity-policy-finalization-') as temporary:
    with fixture.Fixture(artifact,harness,output,Path(temporary).resolve()) as f:
        f.reset('bounded-finalization-drain')
        f.sql("CREATE FUNCTION stall_acknowledgement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(20); RETURN NEW; END $$; CREATE TRIGGER stall_acknowledgement BEFORE INSERT ON policy_acknowledgement FOR EACH ROW EXECUTE FUNCTION stall_acknowledgement()")
        # No JDBC wrapper: the normal production transaction strategy and pool run.
        p=f.spawn('primary',fixture.MAIN,['revoke'],'primary')
        deadline=time.monotonic()+20
        while True:
            row=f.sql("SELECT json_build_object('backend',pid,'transactionStartedAt',xact_start,'observedAt',clock_timestamp()) FROM pg_stat_activity WHERE datname=current_database() AND usename='new_runtime' AND state='active' AND query LIKE 'INSERT INTO policy_acknowledgement%'")
            if row:break
            assert p.poll() is None,'writer exited before stalled acknowledgement was observed'
            assert time.monotonic()<deadline,'stalled acknowledgement not observed'
            time.sleep(.03)
        observed=json.loads(row)
        started=datetime.datetime.fromisoformat(observed['transactionStartedAt'])
        pending=f.state()
        assert pending['revoked'] and pending['acks']==0 and pending['events']==f.baseline['events']+1
        deadline=time.monotonic()+7
        while f.sql("SELECT count(*) FROM pg_stat_activity WHERE pid="+str(observed['backend'])+" AND xact_start IS NOT NULL")!='0':
            assert time.monotonic()<deadline,'finalizer transaction did not drain'
            time.sleep(.03)
        drained=datetime.datetime.now(datetime.timezone.utc)
        seconds=(drained-started).total_seconds()
        assert seconds<=5.25,seconds
        p.wait(timeout=10)
        response=json.loads((output/(f.current+'-primary.log')).read_text().splitlines()[-1])
        assert p.returncode!=0 and not response['ok'] and response['outcome']=='pending',response
        assert f.state()==pending
        assert not f.helper('check',role='primary')['allowed']
        f.sql('DROP TRIGGER stall_acknowledgement ON policy_acknowledgement; DROP FUNCTION stall_acknowledgement()')
        f.helper('resolve');f.helper('revoke',role='primary')
        after=f.state()
        assert after['revoked'] and after['acks']==1 and after['events']==pending['events']
        assert [r['kind'] for r in f.journal()]==['ENROLL','PREPARE','COMMIT']
        f.record(['actual PostgreSQL acknowledgement insert stalled for twenty seconds',
                  'unwrapped production finalizer aborts transaction within its five-second deadline',
                  'caller receives pending and committed restriction remains effective',
                  'exact resolution adds one acknowledgement without repeating effects'],
                 finalizationObserved=observed,drainSeconds=seconds,samplingToleranceSeconds=.25,
                 jdbcFaultWrapper=False,outcome=response['outcome'])
        receipt={'schemaVersion':1,'status':'passed','artifactSha256':fixture.sha(artifact.read_bytes()),
                 'harnessSha256':fixture.sha(harness.read_bytes()),'cases':f.results,'configuration':f.configuration,
                 'procedureSha256':fixture.sha(Path(__file__).read_bytes()),'fixtureProcedureSha256':fixture.sha(source.read_bytes()),
                 'scope':'Bounded acknowledgement finalization after local commit, with actual stalled SQL and the unwrapped production transaction strategy.',
                 'limitations':['Same-host synthetic fixture; no host/disk-loss or organization restore qualification.']}
        (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
        print(json.dumps({'status':'passed','cases':len(f.results),'finalizationDrainSeconds':seconds}),flush=True)
