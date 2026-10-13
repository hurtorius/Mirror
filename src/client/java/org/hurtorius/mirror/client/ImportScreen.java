package org.hurtorius.mirror.client;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.Locale;
public final class ImportScreen extends Screen {
    private final Screen parent;private final Path path;private final String engineId;private final long bytes;private boolean shrink;private EditBox folder;private String folderName="";
    public ImportScreen(Screen parent,Path path,String id){super(Component.literal("Import into this world"));this.parent=parent;this.path=path;engineId=id;long size;try{size=Files.size(path);}catch(java.io.IOException e){size=0;}bytes=size;shrink=suggestShrink(bytes);}
    static boolean video(Path path){return path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|webm|mov|avi)$");}
    static boolean suggestShrink(long bytes){return bytes>100L*1024*1024;}
    protected void init(){int w=Math.min(360,width-24),x=(width-w)/2;folder=addRenderableWidget(new EditBox(font,x,80,w,20,Component.literal("Library folder")));folder.setMaxLength(64);folder.setValue(folderName);folder.setResponder(value->folderName=value);addRenderableWidget(new MirrorButton(x,110,w,"Shrink to 720p for a smaller map: "+(shrink?"on":"off"),()->{shrink=!shrink;rebuildWidgets();}));addRenderableWidget(new MirrorButton(x,height-56,w,"Import into this world",()->{MirrorClient.closeEngine(engineId);if(shrink){MirrorClient.pendingImportFolder=folder.getValue();MirrorClient.filePicker(true).send("TRANSCODE","path",path.toString());}else MirrorClient.importFile(path,folder.getValue());minecraft.setScreen(parent);}));addRenderableWidget(new MirrorButton(x,height-28,w,"Cancel",this::onClose));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.drawCenteredString(font,"Import into this world",width/2,16,0xffffffff);g.drawCenteredString(font,font.plainSubstrByWidth(path.getFileName().toString()+" · "+String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0),width-24),width/2,39,0xffcccccc);g.drawString(font,"Library folder (optional)",folder.getX(),68,0xffcccccc);super.render(g,mx,my,delta);}
    public void onClose(){MirrorClient.closeEngine(engineId);if(MirrorClient.editor!=null)MirrorClient.editor.importCancelled();minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
