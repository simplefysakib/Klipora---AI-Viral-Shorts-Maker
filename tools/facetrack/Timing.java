import com.viral.shortsmaker.core.*;
import java.nio.file.*;
public class Timing {
  public static void main(String[] a) throws Exception {
    FaceTrack.Plan p = new FaceTrack.Plan();
    p.usable = true; p.outFps = 30; p.cropW = 720 * 9.0 / 16 / 1280; p.cx = new double[90];
    for (int k = 0; k < 90; k++) p.cx[k] = k < 45 ? p.cropW / 2 : 1 - p.cropW / 2;
    Files.write(Paths.get("/tmp/ft/h/timing.cmd"), p.sendcmd().getBytes());
    String[] args = FfmpegCommands.renderClip("/tmp/ft/src/norm.mp4", null, 12.3, 3, FfmpegCommands.Frame.BLUR, null, null, "/tmp/ft/h/timing_track.mp4", 720, FfmpegCommands.Encoder.X264, 0, new FfmpegCommands.Track("/tmp/ft/h/timing.cmd", p));
    Files.write(Paths.get("/tmp/ft/h/timing.args"), String.join("\n", args).getBytes());
    // reference: FILL (2.3) graph args, to swap crop for left/right static crops
    String[] ref = FfmpegCommands.renderClip("/tmp/ft/src/norm.mp4", null, 12.3, 3, FfmpegCommands.Frame.FILL, null, null, "/tmp/ft/h/timing_fill.mp4", 720, FfmpegCommands.Encoder.X264, 0);
    Files.write(Paths.get("/tmp/ft/h/timing_fill.args"), String.join("\n", ref).getBytes());
  }
}
