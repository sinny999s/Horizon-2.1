#version 450
#include "shaders/common.glsl"
#include "shaders/palette.glsl"
#include "shaders/environment.glsl"
#include "shaders/cloud-volume.glsl"
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 clouds;
layout(location=1) out vec4 cloudDistance;
uniform sampler2D Depth;
void main() {
    clouds=vec4(0.0); cloudDistance=vec4(60000.0);
    if(abs(WorldTimeWeatherDimension.w)>0.5 || CameraPositionHighAndFogType.w>0.5) return;
    float depth=texture(Depth,texCoord).r;
    float maximum=depth>1e-7?length(viewPosition(texCoord,depth)):60000.0;
    float firstDistance;
    clouds=sulkanCloudVolume(sulkanCloudOrigin()+sulkanCameraOffset(),sulkanRay(texCoord),maximum,CLOUD_QUALITY==1?12:(CLOUD_QUALITY==2?24:(CLOUD_QUALITY==3?40:56)),firstDistance);
    cloudDistance=vec4(firstDistance);
}
