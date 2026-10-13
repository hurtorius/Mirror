#version 330
uniform sampler2D Sampler0;
out vec4 fragColor;
void main() {
    if(texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r >= 0.999999) discard;
    gl_FragDepth = 0.0;
    fragColor = vec4(0.0);
}
