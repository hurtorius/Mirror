#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec4 adjustments;
in vec4 lightColor;
in vec2 texCoord0;
#ifdef FOG
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif
out vec4 fragColor;
void main() {
    vec4 source = texture(Sampler0, texCoord0);
    vec3 encoded = round(adjustments.rgb * 255.0);
    vec3 controls = mix(encoded / 128.0, vec3(2.0), greaterThanEqual(encoded, vec3(255.0)));
    float luminance = dot(source.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 color = mix(vec3(luminance), source.rgb, controls.b);
    color = clamp(((color - 0.5) * controls.g + 0.5) * controls.r, 0.0, 1.0);
    // World targets use alpha for intermediate passes; the completed view is opaque.
    float alpha = adjustments.a * ColorModulator.a;
    if(alpha < 0.01) discard;
    fragColor = vec4(color * lightColor.rgb * ColorModulator.rgb, alpha);
#ifdef FOG
    // A reflected world already contains fog along the complete reflected ray; other pictures fade like vanilla text.
    fragColor = apply_fog(fragColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
