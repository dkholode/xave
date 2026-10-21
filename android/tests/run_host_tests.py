"""Run renderer/storage regressions without a device or downloaded dependencies."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
studio = Path('/Applications/Android Studio.app/Contents')
java = Path(os.environ.get('JAVA_HOME', studio / 'jbr/Contents/Home'))
json_jar = Path(os.environ.get('XAVE_JSON_JAR', studio / 'plugins/design-tools/lib/layoutlib.jar'))
if not json_jar.is_file():
    raise SystemExit('Set XAVE_JSON_JAR to an installed jar containing org.json (Android Studio layoutlib.jar works).')
out = root / 'app/build/host-tests'
out.mkdir(parents=True, exist_ok=True)
sources = [root / 'app/src/main/java/dev/xave/reader' / (name + '.java') for name in ('ReaderDocument', 'DocumentFiles')]
sources.append(root / 'tests/java/dev/xave/reader/ReaderHostTest.java')
subprocess.run([str(java / 'bin/javac'), '-classpath', str(json_jar), '-d', str(out), *map(str, sources)], check=True)
subprocess.run([str(java / 'bin/java'), '-classpath', str(out) + os.pathsep + str(json_jar), 'dev.xave.reader.ReaderHostTest', str(out / 'reader-preview.html')], check=True)
print(out / 'reader-preview.html')
