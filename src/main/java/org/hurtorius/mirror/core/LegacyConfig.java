package org.hurtorius.mirror.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;

/** Bounded conversion of the dev block's MirrorConfig; the original NBT remains on the block. */
public final class LegacyConfig {
    public record Result(ScreenSpec spec,List<String> notes) {}
    private LegacyConfig(){}
    public static Result convert(String raw,Function<String,String> media){
        if(raw==null||raw.length()>65536)throw new IllegalArgumentException("Legacy settings exceed the import limit");
        JsonObject old=JsonParser.parseString(raw).getAsJsonObject(),out=ScreenSpec.JSON.toJsonTree(new ScreenSpec()).getAsJsonObject();List<String> notes=new ArrayList<>();
        String source=string(old,"source","TEXT");String converted=switch(source){case "WEB"->"WEB";case "CAPTURE"->"SHARE";case "WHITEBOARD"->"WHITEBOARD";case "IMAGE","SLIDESHOW","VIDEO","AUDIO","DOCUMENT"->"MEDIA";default->"TEXT";};
        out.addProperty("source",converted);
        for(String key:List.of("text","width","height","yaw","pitch","roll","shape","fit","color","opacity","brightness","contrast","saturation","loop","slideSeconds","followPlayer","locked","scanlines","projector"))if(old.has(key))out.add(key,old.get(key));
        Map<String,String> aliases=Map.ofEntries(Map.entry("offsetX","x"),Map.entry("offsetY","y"),Map.entry("offsetZ","z"),Map.entry("targetPlayer","target"),Map.entry("publicWebUrl","url"),Map.entry("range","viewRange"),Map.entry("audioRange","soundRange"),Map.entry("twoSided","doubleSided"),Map.entry("transitionSeconds","easeSeconds"),Map.entry("projectorDistance","projectionDepth"),Map.entry("edgeWidth","edgeWidth"),Map.entry("tileColumns","wallColumns"),Map.entry("tileRows","wallRows"),Map.entry("tileColumn","wallColumn"),Map.entry("tileRow","wallRow"));
        aliases.forEach((from,to)->{if(old.has(from)&&!old.get(from).isJsonNull())out.add(to,old.get(from));});
        out.addProperty("yaw",ScreenSpec.wrapDegrees(number(out,"yaw",0)));
        out.addProperty("facing",switch(string(old,"facing","FIXED")){case "VIEWER"->"EACH_VIEWER";case "NEAREST"->"NEAREST_PLAYER";case "PLAYER"->"SPECIFIC_PLAYER";case "BILLBOARD"->"BILLBOARD";default->"FIXED";});
        String edge=string(old,"edgeStyle","RUNIC");out.addProperty("edge",!bool(old,"border",true)?"NONE":edge.equals("TV")?"TELEVISION":edge);
        out.addProperty("glow",Math.clamp(number(old,"edgeGlow",0),0,1));out.addProperty("shadow",number(old,"floorShadow",0)>0);out.addProperty("reflection",number(old,"floorReflection",0)>0);
        out.addProperty("volume",bool(old,"sound",false)&&bool(old,"mediaAudio",true)?Math.clamp(number(old,"volume",.25),0,1):0);
        out.addProperty("entrance",switch(string(old,"appearanceTransition","UNFOLD")){case "NONE"->"POP";case "FADE"->"MATERIALIZE";case "IRIS","RISE"->string(old,"appearanceTransition","UNFOLD");default->"UNFOLD";});
        String transition=string(old,"contentTransition","FADE");out.addProperty("transition",Set.of("FADE","SLIDE","ZOOM","DISSOLVE").contains(transition)?transition:"FADE");
        String audience=string(old,"audience","EVERYONE");out.addProperty("audience",switch(audience){case "EVERYONE"->"NEARBY";case "SELECTED"->"SELECTED";default->"NOBODY";});
        out.add("viewers",ScreenSpec.JSON.toJsonTree(split(string(old,"viewers",""))));
        out.add("allowedSites",ScreenSpec.JSON.toJsonTree(split(string(old,"allowedWebHosts",""))));out.add("blockedSites",ScreenSpec.JSON.toJsonTree(split(string(old,"blockedWebHosts",""))));
        List<String> playlist=new ArrayList<>();String primary=string(old,"mediaId","");if(!primary.isEmpty()){String id=media.apply(primary);if(!id.isEmpty())playlist.add(id);else notes.add("Referenced legacy media is unavailable");}
        for(String hash:split(string(old,"playlist",""))){String id=media.apply(hash);if(!id.isEmpty()&&!playlist.contains(id))playlist.add(id);else if(id.isEmpty())notes.add("A legacy playlist item is unavailable");}
        out.add("playlist",ScreenSpec.JSON.toJsonTree(playlist));String subtitle=string(old,"subtitleId","");if(!subtitle.isEmpty())out.addProperty("subtitle",media.apply(subtitle));
        if(string(old,"shape","FLAT").equals("CUSTOM")){var points=PolygonShape.parse(string(old,"customShape",PolygonShape.DEFAULT_CSV)).points().stream().map(p->new Outline.Point((p.x()+1)/2.0,(1-p.y())/2.0)).toList();out.add("outline",ScreenSpec.JSON.toJsonTree(points));}
        if(!string(old,"showTimeline","").isEmpty()||!string(old,"triggerProgram","").isEmpty()||!string(old,"linkId","").isEmpty()||!string(old,"groupId","").isEmpty())notes.add("Legacy show/link/group settings are retained in block NBT for review");
        if(Set.of("OPERATORS","GROUP").contains(audience))notes.add("Legacy audience needs review before enabling");
        if(!string(old,"redstone","IGNORE").equals("IGNORE")||bool(old,"triggersArmed",false))notes.add("Legacy world triggers are retained and require review before enabling");
        out.addProperty("live",bool(old,"enabled",false)&&notes.isEmpty()&&!Set.of("WEB","SHARE","WHITEBOARD").contains(converted));
        out.addProperty("triggersArmed",false);
        return new Result(ScreenSpec.read(out.toString()),notes.stream().distinct().toList());
    }
    private static String string(JsonObject o,String k,String fallback){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():fallback;}
    private static double number(JsonObject o,String k,double fallback){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsDouble():fallback;}
    private static boolean bool(JsonObject o,String k,boolean fallback){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsBoolean():fallback;}
    private static List<String> split(String text){return Arrays.stream(text.split("[,;\\s]+")).map(String::trim).filter(s->!s.isEmpty()).distinct().toList();}
}
