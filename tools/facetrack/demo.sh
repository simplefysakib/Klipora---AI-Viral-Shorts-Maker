set -e
cd /tmp/ft
seg() { # name src start dur W H
  n=$1; src=$2; st=$3; du=$4; W=$5; H=$6
  timeout 600 /home/user/.prime/agent/kernel-venv/bin/python analyze.py $src $st $du h/$n 2>&1 | grep -v "W0000\|I0000\|absl\|INFO\|Warn\|warn"
  java -cp jh/out Harness h/$n.txt $W $H $du h/$n.cmd $src $st h/${n}_on.mp4 | tee h/$n.report
  mapfile -t A < h/$n.cmd.args; ffmpeg -nostdin -loglevel error "${A[@]}"
  # 2.3 render (blur background, his default) via the same command builder with no track
  java -cp jh/out Off $src $st $du h/${n}_off.mp4 > h/${n}_off.args
  mapfile -t B < h/${n}_off.args; ffmpeg -nostdin -loglevel error "${B[@]}"
}
seg normA src/norm.mp4 20 24 1280 720
seg jreA src/jre.mp4 30 16 1920 1080
