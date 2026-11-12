package org.hurtorius.mirror.client;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
public final class MirrorButton extends Button.Plain {
    public MirrorButton(int x,int y,int w,String label,Runnable press){super(x,y,w,20,Component.literal(label),button->press.run(),DEFAULT_NARRATION);}
}
