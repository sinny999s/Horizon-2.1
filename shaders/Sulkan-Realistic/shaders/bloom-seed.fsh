#version 450
#include "shaders/common.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D Source;
void main() {
    vec2 px = 0.5 / vec2(textureSize(Source,0));
    vec3 c = (texture(Source,texCoord+px).rgb + texture(Source,texCoord-px).rgb + texture(Source,texCoord+vec2(px.x,-px.y)).rgb + texture(Source,texCoord+vec2(-px.x,px.y)).rgb)*0.25;
    float brightness = max(c.r,max(c.g,c.b));
    color = vec4(c * smoothstep(0.45,0.95,brightness),1.0);
}
