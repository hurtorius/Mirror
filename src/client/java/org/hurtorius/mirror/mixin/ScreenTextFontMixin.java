package org.hurtorius.mirror.mixin;

import net.minecraft.client.gui.font.FontManager;
import org.hurtorius.mirror.client.MirrorRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FontManager.class)
public abstract class ScreenTextFontMixin {
    // Resource reloads and font options (forced Unicode, glyph variants) rebuild every glyph atlas and advance.
    @Inject(method={"apply","updateOptions"},at=@At("TAIL"))
    private void mirror$fontsChanged(CallbackInfo ci){MirrorRenderer.fontsChanged();}
}
