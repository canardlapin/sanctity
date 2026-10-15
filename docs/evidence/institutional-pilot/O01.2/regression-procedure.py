import os,subprocess,time
image='postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24'
def cmd(args):return subprocess.check_output(args,text=True).strip()
container=cmd(['docker','run','-d','--label','io.sanctity.purpose=service-startup-regression','-e','POSTGRES_USER=sanctity_test','-e','POSTGRES_PASSWORD=ephemeral_test_only','-e','POSTGRES_DB=sanctity_test','-p','127.0.0.1::5432',image])
try:
 deadline=time.monotonic()+60
 while subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','sanctity_test'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
  assert time.monotonic()<deadline;time.sleep(.2)
 port=cmd(['docker','port',container,'5432/tcp']).rsplit(':',1)[1]
 env=dict(os.environ,SANCTITY_TEST_DISPOSABLE='1',SANCTITY_JDBC_URL='jdbc:postgresql://127.0.0.1:'+port+'/sanctity_test',SANCTITY_DB_USER='sanctity_test',SANCTITY_DB_PASSWORD='ephemeral_test_only')
 subprocess.run(['sbt','service/test','integration/testOnly sanctity.postgres.OperationsSuite sanctity.postgres.HttpSuite'],env=env,check=True)
finally:subprocess.run(['docker','rm','-fv',container],stdout=subprocess.DEVNULL,check=True)
