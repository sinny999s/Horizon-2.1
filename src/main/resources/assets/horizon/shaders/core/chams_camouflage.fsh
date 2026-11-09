#version 330
#extension GL_ARB_separate_shader_objects : require

// An animated camouflage pattern inside the chams mask, outlined.
uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec2 uHalfTexelSize;
    vec4 uColor1;
    vec4 uColor2;
    vec4 uColor3;
    vec4 uColor4;
    float uLineWidth;
    float time;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

float patternForPos(vec2 p, float reso, float t) {
    float dist = 0.;
    for(float i=10.; i < 60.; i++) {
        float rads = t + i;
        vec2 ctrlPoint = vec2(sin(rads), cos(rads));
        ctrlPoint *= abs(cos(rads)) * 25.;
        dist += sin(i + reso * distance(p, ctrlPoint));
    }
    return dist;
}

void main() {
    vec4 center = texture(Sampler0, uv);

    if (center.a != 0) {
        vec2 uv1 = uv * 30.0;
        float col = patternForPos(uv1, 4., time);
        float col2 = patternForPos(uv1, 9., time);
        float col3 = patternForPos(uv1, 3., time);
        float col4 = patternForPos(uv1, 2.25, time);

        if(col2 + col + col4 > 2.85)    color = uColor1;
        else if(col2 < 0.81)            color = uColor2;
        else if(abs(col4 * col3) > 4.5) color = uColor3;
        else                            color = uColor3;
        return;
    }

    float alpha = 0;
    for (float x = -2; x <= 2; x++) {
        for (float y = -2; y <= 2; y++) {
            vec4 curColor = texture(Sampler0, uv + vec2(uHalfTexelSize.x * x, uHalfTexelSize.y * y));
            if (curColor.a != 0)
                alpha += max(0, (uLineWidth - sqrt(x * x + y * y)));
        }
    }

    color = vec4(uColor1.rgb, alpha);
}
