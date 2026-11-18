// Reconstruct normals from the closest depth neighbors, never across a silhouette.
vec3 depthSurfaceNormal(sampler2D depthTexture, vec2 uv, mat4 inverseProjection) {
    ivec2 size=textureSize(depthTexture,0);
    ivec2 c=clamp(ivec2(uv*vec2(size)),ivec2(0),size-1);
    ivec2 l=max(c-ivec2(1,0),ivec2(0)), r=min(c+ivec2(1,0),size-1);
    ivec2 d=max(c-ivec2(0,1),ivec2(0)), u=min(c+ivec2(0,1),size-1);
    vec3 p=viewPosition((vec2(c)+0.5)/vec2(size),texelFetch(depthTexture,c,0).r,inverseProjection);
    vec3 pl=viewPosition((vec2(l)+0.5)/vec2(size),texelFetch(depthTexture,l,0).r,inverseProjection);
    vec3 pr=viewPosition((vec2(r)+0.5)/vec2(size),texelFetch(depthTexture,r,0).r,inverseProjection);
    vec3 pd=viewPosition((vec2(d)+0.5)/vec2(size),texelFetch(depthTexture,d,0).r,inverseProjection);
    vec3 pu=viewPosition((vec2(u)+0.5)/vec2(size),texelFetch(depthTexture,u,0).r,inverseProjection);
    vec3 dx=abs(pl.z-p.z)<abs(pr.z-p.z)?p-pl:pr-p;
    vec3 dy=abs(pd.z-p.z)<abs(pu.z-p.z)?p-pd:pu-p;
    vec3 n=cross(dx,dy);
    float lengthSquared=dot(n,n);
    if(lengthSquared<1e-12) return vec3(0.0);
    n*=inversesqrt(lengthSquared);
    return dot(n,-p)<0.0?-n:n;
}

vec3 depthSurfaceNormal(sampler2D depthTexture, vec2 uv) { return depthSurfaceNormal(depthTexture, uv, InverseProjection); }
