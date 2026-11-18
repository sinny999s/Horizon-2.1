#version 450
#include "shaders/common.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D Source;
void main() {
    vec2 px = 2.0/vec2(textureSize(Source,0));
    vec3 c=vec3(0.0);
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) c+=texture(Source,texCoord+vec2(x,y)*px).rgb;
    color=vec4(c/9.0,1.0);
}
