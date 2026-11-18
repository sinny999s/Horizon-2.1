#version 450
#include "shaders/common.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D Source;
void main() {
    vec2 d = vec2(1.0,0.0) / vec2(textureSize(Source,0));
    vec3 c = texture(Source,texCoord).rgb * 0.227027;
    c += (texture(Source,texCoord+d*1.384615).rgb + texture(Source,texCoord-d*1.384615).rgb)*0.316216;
    c += (texture(Source,texCoord+d*3.230769).rgb + texture(Source,texCoord-d*3.230769).rgb)*0.070270;
    color = vec4(c,1.0);
}
