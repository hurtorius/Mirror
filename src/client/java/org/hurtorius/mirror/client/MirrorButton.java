package org.hurtorius.mirror.client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.InputWithModifiers;
public final class MirrorButton extends Button.Plain {
    private final Runnable press;
    public MirrorButton(int x,int y,int w,String label,Runnable press){super(x,y,w,20,Component.literal(label),button->press.run(),DEFAULT_NARRATION);this.press=press;}
    public void onPress(InputWithModifiers input){press.run();}
}
