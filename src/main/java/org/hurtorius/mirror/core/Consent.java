package org.hurtorius.mirror.core;

import java.util.*;

/** A grant is bound to one screen, publisher, immutable audience and source epoch. */
public final class Consent {
    public record Request(String id,String screen,String operator,String publisher,String audience,long expires){}
    public record Grant(String screen,String publisher,String audience,String epoch){}
    private final Map<String,Request> requests=new HashMap<>();
    private final Map<String,Grant> grants=new HashMap<>();
    public Request request(String screen,String operator,String publisher,String audience,long now){
        requests.values().removeIf(r->r.expires<=now || r.screen.equals(screen));
        Request r=new Request(UUID.randomUUID().toString(),screen,operator,publisher,audience,now+120000);requests.put(r.id,r);return r;
    }
    public Grant accept(String request,String publisher,String audience,long now){
        Request r=requests.get(request);
        if(r==null||r.expires<=now||!r.publisher.equals(publisher)||!r.audience.equals(audience))throw new IllegalArgumentException("This sharing request has expired or its audience changed.");
        requests.remove(request);Grant g=new Grant(r.screen,publisher,audience,UUID.randomUUID().toString());grants.put(r.screen,g);return g;
    }
    public Grant accept(String request,String screen,String publisher,String audience,long now){
        Request r=requests.get(request);
        if(r==null||!r.screen.equals(screen))throw new IllegalArgumentException("This invitation belongs to a different screen.");
        return accept(request,publisher,audience,now);
    }
    public boolean allows(String screen,String publisher,String audience,String epoch){Grant g=grants.get(screen);return g!=null&&g.publisher.equals(publisher)&&g.audience.equals(audience)&&g.epoch.equals(epoch);}
    public Grant grant(String screen){return grants.get(screen);}
    public void clear(){requests.clear();grants.clear();}
    public void decline(String request,String publisher){Request r=requests.get(request);if(r!=null&&r.publisher.equals(publisher))requests.remove(request);}
    public void revoke(String screen){grants.remove(screen);requests.values().removeIf(r->r.screen.equals(screen));}
    public void disconnect(String player){requests.values().removeIf(r->r.publisher.equals(player)||r.operator.equals(player));grants.values().removeIf(g->g.publisher.equals(player));}
    public static String audience(ScreenSpec s){List<String> sorted=new ArrayList<>(s.viewers);Collections.sort(sorted);return s.audience+":"+s.viewRange+":"+String.join(",",sorted);}
}
