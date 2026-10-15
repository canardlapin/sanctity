import os,subprocess,time,json,sys
from pathlib import Path
image='postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24'
def cmd(args):return subprocess.check_output(args,text=True).strip()
container=cmd(['docker','run','-d','--label','io.sanctity.purpose=policy-process-regression','-e','POSTGRES_USER=sanctity_test','-e','POSTGRES_PASSWORD=ephemeral_test_only','-e','POSTGRES_DB=sanctity_test','-p','127.0.0.1::5432',image])
try:
 deadline=time.monotonic()+60
 while subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','sanctity_test'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
  assert time.monotonic()<deadline;time.sleep(.2)
 port=cmd(['docker','port',container,'5432/tcp']).rsplit(':',1)[1]
 env=dict(os.environ,SANCTITY_MRI_PYTHON=os.path.abspath('target/mri-python/bin/python'),SANCTITY_TEST_DISPOSABLE='1',SANCTITY_JDBC_URL='jdbc:postgresql://127.0.0.1:'+port+'/sanctity_test',SANCTITY_DB_USER='sanctity_test',SANCTITY_DB_PASSWORD='ephemeral_test_only')
 configuration=cmd(['docker','exec',container,'psql','-U','sanctity_test','-d','sanctity_test','-Atc',"SELECT json_build_object('postgres',version(),'fsync',current_setting('fsync'),'synchronousCommit',current_setting('synchronous_commit'),'fullPageWrites',current_setting('full_page_writes'),'isolation',current_setting('default_transaction_isolation'),'timezone',current_setting('TimeZone'))"])
 Path('docs/evidence/institutional-pilot/O03.6.3/environment.json').write_text(json.dumps(json.loads(configuration),indent=2)+'\n')
 subprocess.run(['sbt']+(sys.argv[1:] or ['integration/testOnly sanctity.postgres.PolicyIntegrationSuite sanctity.postgres.PolicyReceiptCompatibilitySuite sanctity.postgres.ServicePrivilegesSuite sanctity.postgres.OrganizationPersistenceSuite sanctity.postgres.CurrentAuthoritySuite sanctity.postgres.TransactionSuite sanctity.postgres.HttpSuite','service/test','postgres/test','app/testOnly sanctity.app.OrganizationAuthoritySuite sanctity.app.OrganizationSuite']),env=env,check=True)
finally:subprocess.run(['docker','rm','-fv',container],stdout=subprocess.DEVNULL,check=True)
