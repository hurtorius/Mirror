#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
uniform sampler2D Sampler2;
out vec4 adjustments;
out vec4 lightColor;
out vec2 texCoord0;
#ifdef FOG
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    adjustments = Color;
    lightColor = texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
#ifdef FOG
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
#endif
}
