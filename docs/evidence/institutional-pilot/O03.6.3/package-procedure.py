from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[4]
out=Path(__file__).resolve().parent/'final-artifact'
out.mkdir(exist_ok=False)
artifact=root/'target/service-release-o0363-final-20261015/sanctity-0.1.0-SNAPSHOT.zip'
commands=[
 ['python3','scripts/check-service-package.py',str(artifact),'--output',str(out/'package')],
 ['python3','scripts/check-policy-witness-package.py',str(artifact),'--output',str(out/'witness-package')],
 ['python3','scripts/check-policy-catalog-package.py',str(artifact),'--output',str(out/'catalog-package')],
 ['python3','scripts/check-upgrade-compatibility.py','verify','--modern','--organization','--artifact',str(artifact),
  '--fixture','local-artifacts/compatibility/pre-core-20261014/corpus-v2','--output',str(out/'historical-upgrade')]
]
for i,command in enumerate(commands):
 with (out/f'package-{i+1}.log').open('x') as log:
  subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
 print('passed',command[1],flush=True)
