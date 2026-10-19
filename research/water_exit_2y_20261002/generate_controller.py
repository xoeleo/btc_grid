"""Replay the production water controller verbatim except disk journaling; never edit live sources."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parent
source=root.parents[1]/'grid-app/src/main/java/com/btcgrid/app/RebalanceWaterExit.java'
s=source.read_text(); original=s
s=s.replace('public final class RebalanceWaterExit','public final class ReplayWaterExit').replace('public RebalanceWaterExit(', 'public ReplayWaterExit(').replace('if (Files.exists(path))', 'if (path != null && Files.exists(path))')
a=s.index('    private void persist() {')
s=s[:a]+'    private void persist() { /* Replay: no filesystem journal, strategy logic unchanged. */ }\n}\n'
(root/'ReplayWaterExit.java').write_text(s)
(root/'controller_source.json').write_text(json.dumps({'source':str(source),'sha256':hashlib.sha256(original.encode()).hexdigest(),'modifications':['rename for isolated research','skip null path load','replace persist with no-op only']},indent=2))
