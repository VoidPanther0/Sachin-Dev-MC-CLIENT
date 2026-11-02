package doomsday.mc;

/**
 * Manual camera-space perspective projection, independent of Minecraft's
 * internal PoseStack/Matrix4f field layout. Trades a bit of precision
 * (no roll, assumes vanilla FOV handling) for not having to chase the
 * matrix stack's internal representation across versions.
 */
public final class RenderContext {

    public record Screen(double x, double y, double scale) {}

    /** returns null if the point is behind the camera */
    public static Screen project(double[] camPos, float camYaw, float camPitch,
                                  double fovDeg, int screenW, int screenH,
                                  double worldX, double worldY, double worldZ) {
        double dx = worldX - camPos[0];
        double dy = worldY - camPos[1];
        double dz = worldZ - camPos[2];

        double yawRad = Math.toRadians(camYaw);
        double pitchRad = Math.toRadians(camPitch);

        double x1 = dx * Math.cos(yawRad) + dz * Math.sin(yawRad);
        double z1 = -dx * Math.sin(yawRad) + dz * Math.cos(yawRad);

        double y2 = dy * Math.cos(pitchRad) + z1 * Math.sin(pitchRad);
        double z2 = -dy * Math.sin(pitchRad) + z1 * Math.cos(pitchRad);

        if (z2 <= 0.05) return null;

        double f = (screenH / 2.0) / Math.tan(Math.toRadians(fovDeg / 2.0));
        double sx = screenW / 2.0 + (x1 * f) / z2;
        double sy = screenH / 2.0 - (y2 * f) / z2;
        double scale = f / z2; // world-units -> pixels, for sizing the esp box from entity bbox dims

        return new Screen(sx, sy, scale);
    }
}
