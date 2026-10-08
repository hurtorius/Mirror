package org.hurtorius.mirror.core;
import java.util.*;
import java.util.regex.*;
/** Plain SRT/WebVTT captions; markup is rendered as text, never executed. */
public final class Subtitles {
    public record Cue(double start,double end,String text){}
    private static final Pattern TIMING=Pattern.compile("(?m)^(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})\\s+-->\\s+(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})[^\\n]*\\n");
    public static List<Cue> parse(String text){if(text==null||text.length()>262144)throw new IllegalArgumentException("Subtitles must be under 256 KB.");String normalized=text.replace("\r\n","\n").replace('\r','\n');Matcher matches=TIMING.matcher(normalized);List<Cue> result=new ArrayList<>();while(matches.find()){double start=time(matches,1),end=time(matches,5);int stop=normalized.indexOf("\n\n",matches.end());if(stop<0)stop=normalized.length();String body=normalized.substring(matches.end(),stop).replaceAll("<[^>]{0,128}>","").trim();if(start<0||end<=start||end>86400||body.length()>500||result.size()>=4096)throw new IllegalArgumentException("A subtitle cue is invalid or too long.");result.add(new Cue(start,end,body));}if(result.isEmpty())throw new IllegalArgumentException("Choose a valid SRT or WebVTT subtitle file.");result.sort(Comparator.comparingDouble(Cue::start));return List.copyOf(result);}
    private static double time(Matcher m,int index){int hours=m.group(index)==null?0:Integer.parseInt(m.group(index)),minutes=Integer.parseInt(m.group(index+1)),seconds=Integer.parseInt(m.group(index+2)),millis=Integer.parseInt(m.group(index+3));if(minutes>59||seconds>59)throw new IllegalArgumentException("Invalid subtitle time.");return hours*3600+minutes*60+seconds+millis/1000.0;}
    public static String at(List<Cue> cues,double position){for(Cue cue:cues){if(cue.start>position)break;if(cue.start<=position&&cue.end>position)return cue.text;}return "";}
}
