#version 330
#extension GL_ARB_separate_shader_objects : require

// One direction of a gaussian glow of the chams mask's alpha (Sampler1).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

layout(std140) uniform UBO {
     vec2 texelSize;
     vec2 direction;
     float radius;
     float glowAlpha;
     mat4 weights;
     vec4 uColor;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

float getWeight(int i) {
    if (i < 4) return weights[0][i];
    else if (i < 8) return weights[1][i - 4];
    else if (i < 12) return weights[2][i - 8];
    else return weights[3][i - 12];
}

void main() {
     if (direction.y > 0 && texture(Sampler0, uv).a != 0.0)
        discard;

    float blr = texture(Sampler1, uv).a * glowAlpha * getWeight(0);

    vec2 offset = texelSize * direction;

    for (float f = 1.0; f <= radius; f++) {
        int i = int(abs(f));
        float w = getWeight(i) * glowAlpha;

        blr += texture(Sampler1, uv + f * offset).a * w;
        blr += texture(Sampler1, uv - f * offset).a * w;
    }

    color = vec4(uColor.rgb, blr);
}
