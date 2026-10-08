package org.hurtorius.mirror.client;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** World controls stay inside the Initiator and are authorized again by the server. */
public final class ServerSettingsScreen extends Screen {
    private final InitiatorScreen parent;
    private final Map<String,MirrorButton> features=new LinkedHashMap<>();
    private MirrorButton emergencyButton,done,rate;
    private EditBox quota;
    private String quotaValue="",error="";
    private boolean requested,edited,syncing;
    public ServerSettingsScreen(InitiatorScreen parent){super(Component.literal("World controls"));this.parent=parent;}
    protected void init(){
        features.clear();int w=Math.min(320,width-24),x=(width-w)/2,y=34;
        emergencyButton=addRenderableWidget(new MirrorButton(x,y,w,"Emergency stop",()->control("emergency",!MirrorClient.emergency)));
        y+=26;
        for(String feature:new String[]{"browser","media","sharing"}){
            String key=feature;features.put(key,addRenderableWidget(new MirrorButton(x,y,w,key,()->control(key,!MirrorClient.worldFeatures.getOrDefault(key,false)))));y+=26;
        }
        rate=addRenderableWidget(new MirrorButton(x,y,w,"",()->minecraft.setScreen(new ChoiceScreen<>(this,"Shared browser motion",java.util.List.of(new ChoiceScreen.Option<>(60,"Smooth · 60 FPS","Higher frame rate; more network traffic."),new ChoiceScreen.Option<>(30,"Balanced · 30 FPS","Lower network load.")),MirrorClient.publishFps,fps->MirrorClient.send("SERVER_CONTROL","id",parent.anchorId(),"setting","fps","fps",fps)))));y+=26;
        quota=addRenderableWidget(new EditBox(font,x+120,y,w-120,20,Component.literal("Media storage (MB)")));
        quota.setMaxLength(6);quota.setValue(quotaValue);quota.setResponder(value->{quotaValue=value;if(!syncing)edited=true;error="";});
        done=addRenderableWidget(new MirrorButton(width/2-70,height-28,140,"Done",this::save));
        if(!requested){requested=true;MirrorClient.worldSettingsReady=false;MirrorClient.send("SERVER_STATUS");}
        tick();
    }
    private void control(String setting,boolean enabled){MirrorClient.send("SERVER_CONTROL","id",parent.anchorId(),"setting",setting,"enabled",enabled);}
    public void tick(){
        rate.setMessage(Component.literal("Shared frame limit: "+MirrorClient.publishFps+" FPS"));rate.active=MirrorClient.worldSettingsReady;
        emergencyButton.setMessage(Component.literal("Emergency stop: "+(MirrorClient.emergency?"On":"Off")));
        for(var e:features.entrySet()){String name=e.getKey();e.getValue().setMessage(Component.literal(Character.toUpperCase(name.charAt(0))+name.substring(1)+": "+(MirrorClient.worldFeatures.getOrDefault(name,false)?"On":"Off")));e.getValue().active=MirrorClient.worldSettingsReady;}
        quota.active=MirrorClient.worldSettingsReady;done.active=MirrorClient.worldSettingsReady||!error.isEmpty();
        if(MirrorClient.worldSettingsReady&&!edited){String current=Long.toString(MirrorClient.quota/(1024*1024));if(!quota.getValue().equals(current)){syncing=true;quota.setValue(current);syncing=false;}}
    }
    public void error(String message){error=message;}
    private void save(){
        if(!MirrorClient.worldSettingsReady){onClose();return;}
        try{long mb=Long.parseLong(quotaValue);if(mb<1||mb>102400)throw new NumberFormatException();if(mb!=MirrorClient.quota/(1024*1024))MirrorClient.send("SERVER_CONTROL","id",parent.anchorId(),"setting","quota","megabytes",mb);onClose();}
        catch(NumberFormatException e){error="Enter a storage limit from 1 to 102400 MB";setFocused(quota);}
    }
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xff202020);g.drawCenteredString(font,getTitle(),width/2,14,0xffffffff);g.drawString(font,"Media storage (MB)",quota.getX()-120,quota.getY()+6,0xffcccccc);if(!error.isEmpty())g.drawCenteredString(font,font.plainSubstrByWidth(error,width-20),width/2,height-43,0xffffbb66);else if(!MirrorClient.worldSettingsReady)g.drawCenteredString(font,"Loading…",width/2,height-43,0xffcccccc);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
