package org.hurtorius.mirror.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;
import java.util.HashMap;
import java.util.Map;

/** Every picture applies its brightness, contrast and saturation on the GPU from ReflectionStyle vertex colors. */
final class ReflectionRenderType {
    private static final RenderPipeline.Snippet PICTURE=RenderPipeline.builder(RenderPipelines.TEXT_SNIPPET)
        .withVertexShader(Identifier.parse("mirror:core/reflection"))
        .withFragmentShader(Identifier.parse("mirror:core/reflection"))
        .withSampler("Sampler0").withSampler("Sampler2").buildSnippet();
    // A reflection already holds fog along its reflected rays; other pictures fade into fog like vanilla text.
    private static final RenderPipeline REFLECTION=RenderPipeline.builder(PICTURE).withLocation(Identifier.parse("mirror:pipeline/reflection")).build(),
        FOGGED=RenderPipeline.builder(PICTURE,RenderPipelines.FOG_SNIPPET).withLocation(Identifier.parse("mirror:pipeline/picture")).withShaderDefine("FOG").build();
    private static final Map<Identifier,RenderType> types=new HashMap<>();
    static RenderType of(Identifier texture){
        if(!texture.getPath().startsWith("frames/"))return RenderTypes.text(texture);
        return types.computeIfAbsent(texture,id->RenderType.create("mirror_picture",RenderSetup.builder(id.getPath().startsWith("frames/reflection-")?REFLECTION:FOGGED).withTexture("Sampler0",id).useLightmap().bufferSize(65536).createRenderSetup()));
    }
    static void remove(Identifier texture){types.remove(texture);}
    static void reset(){types.clear();}
}
