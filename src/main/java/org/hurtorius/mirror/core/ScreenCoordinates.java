package org.hurtorius.mirror.core;

/** Editor world coordinates over the existing saved offsets; old worlds do not move. */
public record ScreenCoordinates(double x, double y, double z) {
    public static final double REACH = 128;
    public static ScreenCoordinates at(Anchor anchor) { return new ScreenCoordinates(anchor.x + .5, anchor.y, anchor.z + .5); }
    public static ScreenCoordinates followedPlayer() { return new ScreenCoordinates(0, 0, 0); }
    public static boolean isAxis(String name) { return name.equals("x") || name.equals("y") || name.equals("z"); }
    public double origin(String axis) { return switch(axis) { case "x" -> x; case "y" -> y; case "z" -> z; default -> throw new IllegalArgumentException("Unknown coordinate"); }; }
    public double display(String axis, double offset) { return origin(axis) + offset; }
    public double stored(String axis, double coordinate) {
        double offset = coordinate - origin(axis);
        ScreenSpec.range(coordinate, origin(axis) - REACH, origin(axis) + REACH, "Center " + axis.toUpperCase());
        return offset;
    }
    public double snapped(String axis, double offset) { return stored(axis, Math.clamp(Math.rint(display(axis, offset)), Math.ceil(origin(axis)-REACH), Math.floor(origin(axis)+REACH))); }
}
