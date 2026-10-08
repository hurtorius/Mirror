package org.hurtorius.mirror.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.net.URI;
import java.util.*;

/** Persistent, versioned user intent. No credentials, capture handles or live grants. */
public final class ScreenSpec {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public enum Source { BLANK, MEDIA, WEB, SHARE, WHITEBOARD, TEXT }
    public enum Facing { FIXED, EACH_VIEWER, NEAREST_PLAYER, BILLBOARD, SPECIFIC_PLAYER, ORBIT, MOUNTED }
    public enum Shape { FLAT, CONCAVE, CONVEX, PANORAMA, CIRCLE, OVAL, ROUNDED, HEXAGON, CUSTOM }
    public enum Edge { NONE, BEZEL, TELEVISION, NEON, CARVED, RUNIC, FADE }
    public enum Fit { FIT, FILL, STRETCH, ORIGINAL }
    public enum Transition { FADE, SLIDE, ZOOM, DISSOLVE }
    public enum Entrance { UNFOLD, IRIS, RISE, MATERIALIZE, POP }
    public enum Audience { NEARBY, SELECTED, NOBODY }
    public enum Trigger { MANUAL, REDSTONE, AREA }
    public enum Redstone { NONE, POWER, PULSE_NEXT }

    public int schema = 1;
    public String name = "Mirror";
    public String text = "";
    public boolean triggersArmed = false;
    public Source source = Source.BLANK;
    public String url = "";
    public List<String> playlist = new ArrayList<>();
    public int item = 0;
    public boolean loop = true;
    public double slideSeconds = 8;
    public String subtitle = "";
    public double width = 6, height = 3.375;
    public boolean browserAutoSize = true;
    public int browserWidth = 1280, browserHeight = 720;
    public boolean emissive = true;
    public int browserFps = 60;
    public boolean backMirrored = false;
    public Shape shape = Shape.FLAT;
    public List<Outline.Point> outline=new ArrayList<>(List.of(new Outline.Point(0,0),new Outline.Point(1,0),new Outline.Point(1,1),new Outline.Point(0,1)));
    public Fit fit = Fit.FIT;
    public Edge edge = Edge.NONE;
    public int color = 0x86e1db;
    public double edgeWidth = .06, glow = 0, opacity = 1, brightness = 1, contrast = 1, saturation = 1;
    public boolean scanlines = false, doubleSided = false, shadow = false, reflection = false;
    public Facing facing = Facing.FIXED;
    public double x = 0, y = 3.5, z = 0, yaw = 0, pitch = 0, roll = 0;
    public String target = "";
    public boolean followPlayer = false;
    public double orbitRadius = 3, orbitSeconds = 20;
    public double easeSeconds = .6, floatAmount = 0, swayDegrees = 0;
    public Entrance entrance = Entrance.UNFOLD;
    public Transition transition = Transition.FADE;
    public List<Waypoint> path = new ArrayList<>();
    public boolean pathLoop = true;
    public double volume = .7, soundRange = 24;
    public boolean positional = true, wideStereo = true, duckMusic = true;
    public Audience audience = Audience.NEARBY;
    public List<String> viewers = new ArrayList<>();
    public double viewRange = 64;
    public String group = "";
    public boolean allowSharing = true;
    public List<String> sharePlayers = new ArrayList<>();
    public List<String> allowedSites = new ArrayList<>(), blockedSites = new ArrayList<>();
    public boolean locked = false;
    public Trigger trigger = Trigger.MANUAL;
    public double areaRadius = 5;
    public List<Cue> show = new ArrayList<>();
    public List<Branch> branches = new ArrayList<>();
    public boolean resetShow = true;
    public Redstone redstone = Redstone.NONE;
    public String link = "";
    public boolean projector = false;
    public double projectionDepth = 6;
    public int wallColumns = 1, wallRows = 1, wallColumn = 0, wallRow = 0;
    public boolean live = false;

    public record Waypoint(double x, double y, double z, double yaw, double pitch, double seconds) {}
    public record Cue(String action, String media, double seconds, double value) {}
    public record Branch(String side,String media) {}

    public String currentMedia(){return source==Source.MEDIA&&!playlist.isEmpty()&&item>=0&&item<playlist.size()?playlist.get(item):"";}
    public static double wrapDegrees(double angle){return Double.isFinite(angle)?((angle+180)%360+360)%360-180:0;}
    public ScreenSpec copy() { return read(JSON.toJson(this)); }
    public static ScreenSpec read(String text) {
        if (text == null || text.length() > 22000) throw new IllegalArgumentException("Screen settings are too large.");
        ScreenSpec spec;
        try { spec = JSON.fromJson(text, ScreenSpec.class); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Screen settings could not be read."); }
        if (spec == null) throw new IllegalArgumentException("Screen settings are missing.");
        spec.validate(); return spec;
    }
    public void validate() {
        if (schema != 1) fail("This screen uses an unsupported settings version.");
        if (source == null || shape == null || fit == null || edge == null || facing == null || entrance == null || transition == null || audience == null || trigger == null || redstone == null) fail("Choose valid screen options.");
        name = text(name, 64, "Screen name"); text = text(text, 2048, "Screen text"); url = text(url, 2048, "Website");
        if (!url.isBlank()) webUri(url);
        target = uuidOrEmpty(target); group = text(group, 32, "Group"); link = uuidOrEmpty(link);
        subtitle = text(subtitle, 2048, "Subtitle");
        ids(playlist, 128, "Playlist"); ids(viewers, 128, "Audience"); ids(sharePlayers, 128, "Sharing list");
        domains(allowedSites); domains(blockedSites);
        range(width,.25,64,"Width"); range(height,.25,36,"Height"); range(edgeWidth,0,.5,"Edge width");
        Outline.validate(outline);
        if(browserFps!=30&&browserFps!=60)fail("Choose 30 or 60 browser frames per second.");
        if(!browserAutoSize){range(browserWidth,16,1920,"Browser width"); range(browserHeight,16,1920,"Browser height");
        if((long)browserWidth*browserHeight>2073600)fail("Browser size must be at most 2,073,600 pixels.");}
        range(glow,0,1,"Glow"); range(opacity,.05,1,"Opacity"); range(brightness,0,2,"Brightness");
        range(contrast,0,2,"Contrast"); range(saturation,0,2,"Saturation");
        range(x,-128,128,"Horizontal offset"); range(y,-128,128,"Vertical offset"); range(z,-128,128,"Depth offset");
        range(yaw,-360,360,"Facing angle"); range(pitch,-90,90,"Tilt"); range(roll,-180,180,"Roll");
        range(orbitRadius,0,64,"Orbit radius"); range(orbitSeconds,1,3600,"Orbit time");
        range(easeSeconds,0,10,"Motion time"); range(floatAmount,0,2,"Float"); range(swayDegrees,0,15,"Sway");
        range(volume,0,1,"Volume"); range(soundRange,1,128,"Sound distance"); range(viewRange,1,128,"View distance");
        range(slideSeconds,1,3600,"Slide time"); range(areaRadius,1,64,"Trigger area"); range(projectionDepth,.5,64,"Projection distance");
        if (color < 0 || color > 0xffffff) fail("Choose a valid edge color.");
        if (item < 0 || item >= Math.max(1,playlist.size())) fail("Choose an item in the playlist.");
        if (wallColumns < 1 || wallColumns > 8 || wallRows < 1 || wallRows > 8 || wallColumn < 0 || wallColumn >= wallColumns || wallRow < 0 || wallRow >= wallRows) fail("The screen-wall tile is outside the wall.");
        if (path == null || path.size() > 64) fail("A flight path can have up to 64 stops.");
        for (Waypoint p : path) { if(p==null)fail("Flight stop is missing.");range(p.x,-128,128,"Path x");range(p.y,-128,128,"Path y");range(p.z,-128,128,"Path z");range(p.yaw,-360,360,"Path yaw");range(p.pitch,-90,90,"Path tilt");range(p.seconds,.1,3600,"Path duration"); }
        if (show == null || show.size() > 128) fail("A show can have up to 128 cues.");
        for (Cue cue : show) {
            if (cue == null || !Set.of("summon","dismiss","play","pause","wait","next","seek","width","height","yaw","pitch","roll","x","y","z").contains(cue.action)) fail("Choose a supported show action.");
            uuidOrEmpty(cue.media); range(cue.seconds,0,3600,"Cue wait"); range(cue.value,-360,86400,"Cue value");
            if(cue.action.equals("width"))range(cue.value,.25,64,"Cue width");
            if(cue.action.equals("height"))range(cue.value,.25,36,"Cue height");
            if(Set.of("x","y","z").contains(cue.action))range(cue.value,-128,128,"Cue offset");
            if(cue.action.equals("yaw"))range(cue.value,-360,360,"Cue facing");
            if(cue.action.equals("pitch"))range(cue.value,-90,90,"Cue tilt");
            if(cue.action.equals("roll"))range(cue.value,-180,180,"Cue roll");
            if(cue.action.equals("seek"))range(cue.value,0,86400,"Cue position");
        }
        if(branches==null||branches.size()>6)fail("Use up to six redstone choices.");
        Set<String> sides=new HashSet<>();
        for(Branch branch:branches)if(branch==null||!Set.of("NORTH","SOUTH","EAST","WEST","UP","DOWN").contains(branch.side)||!sides.add(branch.side)||!playlist.contains(branch.media))fail("Each redstone side needs one item from this playlist.");
        if(JSON.toJson(this).length()>22000)fail("These settings exceed the screen message budget. Use fewer cues, stops or audience entries.");
        if ((facing == Facing.SPECIFIC_PLAYER || followPlayer) && target.isEmpty()) fail("Choose a player to follow or face.");
    }
    public boolean canView(String id, double distance) { return distance <= viewRange && audience != Audience.NOBODY && (audience == Audience.NEARBY || viewers.contains(id)); }
    public boolean siteAllowed(String url) {
        URI uri; try {uri=webUri(url);}catch(IllegalArgumentException e){return false;}
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        return blockedSites.stream().noneMatch(d->matchesDomain(host,d)) && (allowedSites.isEmpty() || allowedSites.stream().anyMatch(d->matchesDomain(host,d)));
    }
    public static boolean matchesDomain(String host,String domain){return host.equals(domain)||host.endsWith("."+domain);}
    public static URI webUri(String value) {
        try {URI uri=URI.create(value); if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null) fail("Use an http or https website without embedded credentials."); return uri;}
        catch(RuntimeException e){throw new IllegalArgumentException("Use an http or https website without embedded credentials.");}
    }
    private static void domains(List<String> list){if(list==null||list.size()>64)fail("Use up to 64 website domains.");for(String domain:list)if(domain==null||domain.length()>253||!domain.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))fail("Website rules need lowercase domain names, such as example.com.");}
    private static void ids(List<String> list,int max,String label){if(list==null||list.size()>max)fail(label+" is too large.");for(String id:list)if(uuidOrEmpty(id).isEmpty())fail(label+" has an empty entry.");if(new HashSet<>(list).size()!=list.size())fail(label+" has duplicate entries.");}
    public static String uuidOrEmpty(String id){if(id==null)fail("An identifier is missing.");if(id.isEmpty())return id;try{if(!UUID.fromString(id).toString().equals(id))fail("An identifier is invalid.");return id;}catch(RuntimeException e){throw new IllegalArgumentException("An identifier is invalid.");}}
    public static String text(String value,int max,String label){if(value==null||value.length()>max||value.codePoints().anyMatch(c->c<32&&c!='\n'&&c!='\t'))fail(label+" is invalid or too long.");return value;}
    public static void range(double n,double min,double max,String label){if(!Double.isFinite(n)||n<min||n>max)fail(label+" must be between "+min+" and "+max+".");}
    private static void fail(String text){throw new IllegalArgumentException(text);}
}
