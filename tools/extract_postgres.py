"""Extract a downloaded, publisher-provided test binary bundle beneath .tools only."""
from pathlib import Path
import tarfile
import zipfile

root = (Path(__file__).resolve().parent.parent / '.tools').resolve()
destination = root / 'postgres'
destination.mkdir(exist_ok=True)
with zipfile.ZipFile(root / 'postgres-binaries.jar') as jar:
    entries = [n for n in jar.namelist() if n.endswith(('.txz', '.tar.xz', '.tgz', '.tar.gz'))]
    if len(entries) != 1:
        raise ValueError(f'Expected one binary archive; found {entries}')
    with jar.open(entries[0]) as archive:
        with tarfile.open(fileobj=archive, mode='r|*') as files:
            files.extractall(destination, filter='data')
print(destination)
