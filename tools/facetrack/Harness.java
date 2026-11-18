import com.viral.shortsmaker.core.*;
import java.nio.file.*; import java.util.*;
public class Harness {
  public static void main(String[] a) throws Exception {
    // args: samples.txt srcW srcH dur cmdOut [ass]
    List<FaceTrack.Sample> s = FaceTrack.samplesFromText(new String(Files.readAllBytes(Paths.get(a[0]))));
    FaceTrack.Plan p = FaceTrack.plan(s, 5, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Double.parseDouble(a[3]), 30);
    Files.write(Paths.get(a[4]), p.sendcmd().getBytes());
    double[] j = p.jitter(720);
    StringBuilder cx = new StringBuilder();
    for (double v : p.cx) cx.append(String.format(Locale.US, "%.5f ", v));
    Files.write(Paths.get(a[4] + ".cx"), cx.toString().getBytes());
    System.out.println("usable=" + p.usable + " why=" + p.why + " " + p.summary() + String.format(Locale.US, " hardcuts=%d jitterStd=%.3fpx max=%.2fpx mean=%.3fpx panel=%.3fx%.3f", p.hardCuts, j[0], j[1], j[2], p.panelW, p.panelH));
    for (FaceTrack.Split sp : p.splits) System.out.println(String.format(Locale.US, "split %.1f-%.1f top(%.2f,%.2f) bot(%.2f,%.2f)", sp.start, sp.end, sp.topCx, sp.topCy, sp.botCx, sp.botCy));
    if (p.usable) {
      String[] args = FfmpegCommands.renderClip(a[5], null, Double.parseDouble(a[6]), Double.parseDouble(a[3]), FfmpegCommands.Frame.BLUR,
          a.length > 8 ? a[8] : null, null, a[7], 720, FfmpegCommands.Encoder.X264, 0, new FfmpegCommands.Track(a[4], p));
      Files.write(Paths.get(a[4] + ".args"), String.join("\n", args).getBytes());
    }
  }
}
