package org.hurtorius.mirror.client;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.Locale;
public final class ImportScreen extends Screen {
    private final Screen parent;private final Path path;private final String engineId;private boolean shrink=false;private EditBox folder;private String folderName="";
    public ImportScreen(Screen parent,Path path,String id){super(Component.literal("Import into this world"));this.parent=parent;this.path=path;engineId=id;}
    protected void init(){int w=Math.min(360,width-24),x=(width-w)/2;folder=addRenderableWidget(new EditBox(font,x,80,w,20,Component.literal("Library folder")));folder.setMaxLength(64);folder.setValue(folderName);folder.setResponder(value->folderName=value);boolean video=path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|webm|mov|avi)$");if(video)addRenderableWidget(new MirrorButton(x,110,w,"Shrink to 720p for a smaller map: "+(shrink?"on":"off"),()->{shrink=!shrink;rebuildWidgets();}));addRenderableWidget(new MirrorButton(x,height-56,w,"Import into this world",()->{if(shrink){MirrorClient.engine(engineId,false,true).send("TRANSCODE","path",path.toString());MirrorClient.pendingImportFolder=folder.getValue();}else MirrorClient.importFile(path,folder.getValue(),false);minecraft.setScreen(parent);}));addRenderableWidget(new MirrorButton(x,height-28,w,"Cancel",this::onClose));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xff14243c);g.drawCenteredString(font,"Import into this world",width/2,16,0xffd6bdf7);g.drawCenteredString(font,font.plainSubstrByWidth(path.getFileName().toString(),width-24),width/2,39,0xffd5e2ed);g.drawString(font,"Library folder (optional)",folder.getX(),68,0xffa2b1c8);super.render(g,mx,my,delta);}
    public void onClose(){if(engineId.equals("picker"))MirrorClient.closeEngine(engineId);if(MirrorClient.editor!=null)MirrorClient.editor.importCancelled();minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
