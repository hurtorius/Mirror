package org.hurtorius.mirror.client;

import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.resources.Identifier;

/** A single frame's authorized picture and immutable geometry. No source controller is retained. */
record DraftPreviewState(int x0,int y0,int x1,int y1,ScreenRectangle scissorArea,
                         DraftPreviewGeometry.Scene scene,Identifier picture,boolean active,boolean glow,
                         List<NativeTextPreview.Batch> text,int light) implements PictureInPictureRenderState {
    @Override public float scale(){return 1;}
    @Override public ScreenRectangle bounds(){return PictureInPictureRenderState.getBounds(x0,y0,x1,y1,scissorArea);}
}
