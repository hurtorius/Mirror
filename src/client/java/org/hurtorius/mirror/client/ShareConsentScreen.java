package org.hurtorius.mirror.client;
import com.google.gson.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
public final class ShareConsentScreen extends Screen {
    private final JsonObject request;private final Screen parent;private final EngineProcess engine;
    private final String id,requestId;private final long expires;private final long localExpires=System.currentTimeMillis()+120000;
    private final List<JsonObject> sources=new ArrayList<>();private int selected=0;
    private boolean sound=false;private String feedback="";private boolean sourcesLoaded;
    private MirrorButton accept;
    private boolean approved,approvedSound;
    private JsonObject approvedSource;
    public ShareConsentScreen(JsonObject m,Screen parent){super(Component.literal("Share your screen?"));this.request=m;this.parent=parent;JsonObject r=m.getAsJsonObject("request");id=r.get("screen").getAsString();requestId=r.get("id").getAsString();expires=r.get("expires").getAsLong();MirrorClient.closeEngine(id);MirrorClient.clearPicture(id);engine=MirrorClient.engine(id,false,false);engine.send("SOURCES");}
    protected void init(){int w=Math.min(380,width-24),x=(width-w)/2;
        String name=sources.isEmpty()?(sourcesLoaded?"No sources available":"Loading sources…"):sources.get(selected).get("name").getAsString();
        var select=addRenderableWidget(new MirrorButton(x,78,w,name,()->{
            List<ChoiceScreen.Option<Integer>> options=new ArrayList<>();
            for(int i=0;i<sources.size();i++){var source=sources.get(i);options.add(new ChoiceScreen.Option<>(i,source.get("name").getAsString(),source.get("id").getAsString().startsWith("window:")?"Window":"Display"));}
            minecraft.setScreen(new ChoiceScreen<>(this,"Choose a window or display",options,selected,index->selected=index));
        }));select.active=!approved&&!sources.isEmpty();
        var audio=addRenderableWidget(new MirrorButton(x,104,w,"Sound: "+(sound?"On":"Off"),()->{sound=!sound;rebuildWidgets();}));audio.active=!approved;
        addRenderableWidget(new MirrorButton(x,height-28,w/2-2,"Cancel",this::onClose));
        accept=addRenderableWidget(new MirrorButton(x+w/2+2,height-28,w/2-2,"Share",()->{
            if(!sources.isEmpty()&&System.currentTimeMillis()+MirrorClient.serverOffset<expires&&System.currentTimeMillis()<localExpires){approved=true;approvedSound=sound;approvedSource=sources.get(selected).deepCopy();MirrorClient.send("CONSENT_ACCEPT","id",id,"request",requestId);feedback="Connecting…";rebuildWidgets();}
        }));accept.active=!approved&&!sources.isEmpty()&&System.currentTimeMillis()+MirrorClient.serverOffset<expires&&System.currentTimeMillis()<localExpires;
    }
    public boolean mayStart(String screen){return approved&&approvedSource!=null&&id.equals(screen)&&System.currentTimeMillis()+MirrorClient.serverOffset<expires&&System.currentTimeMillis()<localExpires;}
    public void accepted(String screen){if(!mayStart(screen))return;approved=false;JsonObject source=approvedSource;engine.send("SCREEN","selection",source.get("id").getAsString(),"pid",source.get("pid").getAsInt());if(approvedSound){long pid=source.get("pid").getAsLong();engine.send("AUDIO","enabled",true,"pid",pid==0?ProcessHandle.current().pid():pid,"exclude",pid==0);}minecraft.setScreen(parent);}
    public void failed(String message){approved=false;approvedSource=null;feedback=message;rebuildWidgets();}
    public void engineEvent(JsonObject m){if(m.get("type").getAsString().equals("SOURCES")){sources.clear();for(JsonElement e:m.getAsJsonArray("sources"))sources.add(e.getAsJsonObject());sourcesLoaded=true;selected=Math.min(selected,Math.max(0,sources.size()-1));feedback=sources.isEmpty()?"No windows or displays were found.":"";rebuildWidgets();}else if(m.get("type").getAsString().equals("ERROR"))failed(m.get("text").getAsString());}
    public void tick(){if(accept!=null&&(System.currentTimeMillis()+MirrorClient.serverOffset>=expires||System.currentTimeMillis()>=localExpires)){accept.active=false;feedback="This request expired. The operator can ask again.";}}
    public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xff202020);g.drawCenteredString(font,"Choose a source",width/2,12,0xffffffff);int w=Math.min(380,width-24),x=(width-w)/2;
        String requester=request.get("operatorName").getAsString(),screen=request.get("screenName").getAsString();
        String intro=requester.equals(minecraft.player.getName().getString())?"Screen: "+screen:requester+" asks to display your screen on “"+screen+"”.";
        var lines=font.split(Component.literal(intro),w);for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(font,lines.get(i),x,32+i*10,0xffcccccc,false);
        String audience="Audience: "+request.get("audienceText").getAsString();g.drawString(font,font.plainSubstrByWidth(audience,w),x,58,0xffcccccc);
        if(mx>=x&&mx<x+w&&my>=56&&my<70)g.setTooltipForNextFrame(font,Component.literal(audience),mx,my);
        super.render(g,mx,my,delta);if(!feedback.isBlank())g.drawString(font,font.plainSubstrByWidth(feedback,width-24),12,height-44,0xffffbb66);
    }
    public void onClose(){approved=false;approvedSource=null;MirrorClient.send("CONSENT_DECLINE","id",id,"request",requestId);MirrorClient.closeEngine(id);minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
