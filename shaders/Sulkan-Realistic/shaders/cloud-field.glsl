// Vanilla cloud layout and movement, reconstructed as a continuous volume.
uniform sampler2D VanillaClouds;
float cloudBottom() { return CloudOffsetAndGameTime.y+CLOUD_HEIGHT_OFFSET; }
vec3 sulkanCloudOrigin() {
    vec2 period=vec2(textureSize(VanillaClouds,0))*12.0;
    return vec3(mod(CameraPositionHighAndFogType.x,period.x),CameraPositionHighAndFogType.y,
                mod(CameraPositionHighAndFogType.z,period.y))+CameraPositionLowAndFarPlane.xyz;
}
vec2 cloudGrid(vec3 p) { return (p.xz+vec2(CloudOffsetAndGameTime.x,3.960000038))/12.0; }
vec4 cloudCell(vec2 cell) {
    ivec2 size=textureSize(VanillaClouds,0);
    ivec2 index=ivec2(mod(cell,vec2(size)));
    vec4 sampleColor=texelFetch(VanillaClouds,index,0);
    float occupied=step(10.0/255.0,sampleColor.a);
    // Empty texels must not darken the color at a cloud's silhouette.
    return vec4(sampleColor.rgb*occupied,occupied);
}
vec4 cloudField(vec3 p, out bool emptyCell) {
    vec2 grid=cloudGrid(p)-0.5;
    vec2 cell=floor(grid), weight=fract(grid);
    weight=weight*weight*(3.0-2.0*weight);
    vec4 a=cloudCell(cell), b=cloudCell(cell+vec2(1,0));
    vec4 c=cloudCell(cell+vec2(0,1)), d=cloudCell(cell+vec2(1,1));
    emptyCell=(a.a+b.a+c.a+d.a)==0.0;
    vec4 field=mix(mix(a,b,weight.x),mix(c,d,weight.x),weight.y);
    field.rgb/=max(field.a,0.0001);
    // A low outer threshold adds a soft fringe while adjacent occupied cells
    // stay solid, without an internal edge at every texture tile.
    field.a=smoothstep(0.08,0.85,field.a);
    return field;
}
vec4 cloudField(vec3 p) { bool emptyCell; return cloudField(p,emptyCell); }
float cloudExtinction() { return 0.65*max(CLOUD_COVERAGE,0.0)/0.5; }
float cloudHeightDensity(vec3 p) {
    float height=p.y-cloudBottom();
    return smoothstep(0.0,0.6,height)*smoothstep(0.0,0.6,4.0-height);
}
float cloudDensity(vec3 p) {
    if(p.y<=cloudBottom() || p.y>=cloudBottom()+4.0) return 0.0;
    return cloudField(p).a*cloudHeightDensity(p)*cloudExtinction();
}
