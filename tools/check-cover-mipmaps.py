"""Run cover mip layout and staging regressions against cached Kotlin tools; no Android runtime or stubs."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

root = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
studio_java = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java')
java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else (str(studio_java) if studio_java.is_file() else shutil.which('java'))
if java is None:
    raise SystemExit('Set JAVA_HOME or install a Java runtime on PATH.')
jars = []
for group, artifact in [
    ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib'),
    ('org.jetbrains.kotlin', 'kotlin-reflect'),
    ('org.jetbrains.kotlin', 'kotlin-script-runtime'),
    ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm'),
    ('org.jetbrains.intellij.deps', 'trove4j'),
    ('org.jetbrains', 'annotations'),
]:
    folder = root / group / artifact
    if not folder.exists():
        continue
    versions = sorted(folder.iterdir())
    version = folder / '2.4.21' if (folder / '2.4.21').exists() else versions[-1]
    jars += [str(p) for p in version.rglob('*.jar') if not any(k in p.name for k in ('sources', 'javadoc'))]
classpath = os.pathsep.join(jars)
with tempfile.TemporaryDirectory(prefix='folia-cover-mipmaps-') as output:
    subprocess.run([java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', output,
                    'app/src/main/java/io/github/techuouo520/folia/spatial/DailySpatialMipmaps.kt',
                    'app/src/main/java/io/github/techuouo520/folia/spatial/DailySpatialPixels.kt',
                    'tools/spatial-tests/CoverMipmapsTest.kt'], check=True)
    subprocess.run([java, '-cp', output + os.pathsep + classpath,
                    'io.github.techuouo520.folia.spatial.CoverMipmapsTestKt'], check=True)
