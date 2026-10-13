package org.hurtorius.mirror.core;
import java.util.*;
/** Client-local one-shot intent. A server acknowledgement alone cannot publish private pixels. */
public final class PublicationIntent {
    private record Pending(String source,long expires){}
    private final Map<String,Pending> requests=new HashMap<>();
    public void request(String screen,String source,long now){requests.put(screen,new Pending(source,now+15000));}
    public boolean accept(String screen,String source,long now){Pending p=requests.remove(screen);return p!=null&&p.source.equals(source)&&now<=p.expires;}
    public void cancel(String screen){requests.remove(screen);}
    public void clear(){requests.clear();}
}
