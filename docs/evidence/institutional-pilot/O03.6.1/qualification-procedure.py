"""Build and qualify this component's distribution after successful source tests."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[4]
out=Path(__file__).resolve().parent
package=root/'target/service-release-o0361-final-20261014'
artifact=package/'sanctity-0.1.0-SNAPSHOT.zip'
commands=[['python3','scripts/package-service.py','--output',str(package)],
          ['python3','scripts/check-policy-witness-package.py',str(artifact),'--output',str(root/'target/witness-smoke-o0361-20261014')],
          ['python3','scripts/check-service-package.py',str(artifact),'--output',str(root/'target/service-smoke-o0361-20261014')]]
with (out/'qualification.log').open('w') as log:
    for command in commands:
        subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
print('Standalone witness and existing packaged service checks passed.')
