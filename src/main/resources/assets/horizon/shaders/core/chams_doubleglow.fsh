#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;

layout(std140) uniform UBO {
     float uTime;
     vec4 uColor1;
     vec4 uColor2;
     int colorMode;
     int glowQuality;
     float glowRadius;
     float glowAlphaMult;
     int glowThinOutline;
     float fillAlphaMult;
     vec2 uHalfTexelSize;
     float uSaturation;
     float uBrightness;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

vec3 wave(vec2 pos)
{
    return mix(uColor1.rgb, uColor2.rgb, sin((distance(vec2(0), pos) - uTime * 60.0) / 60.) * 0.5 + 0.5);
}

float rand(vec2 n) {
	return fract(sin(dot(n, vec2(12.9898, 4.1414))) * 43758.5453);
}

float noise(vec2 p){
	vec2 ip = floor(p);
	vec2 u = fract(p);
	u = u*u*(3.0-2.0*u);

	float res = mix(
		mix(rand(ip),rand(ip+vec2(1.0,0.0)),u.x),
		mix(rand(ip+vec2(0.0,1.0)),rand(ip+vec2(1.0,1.0)),u.x),u.y);
	return res*res;
}

void main() {
    vec4 prevColor = texture(Sampler0, uv);
    vec4 outputColor = uColor1;
    switch(colorMode) {
        case 0: {
            outputColor = uColor1;
            break;
        }
        case 1: {
            vec3 p = -3.1416 * vec3(0.0, 0.5, 1.0);
            float diagonal = (uv.x + uv.y) * 0.5;
            float dist = diagonal * 10.0 + uTime;
            vec3 col = 0.5 + 0.5 * -sin(dist) * cos(dist + p);

            vec3 gray = vec3(dot(col, vec3(0.299, 0.587, 0.114)));
            col = mix(gray, col, uSaturation);

            outputColor = vec4(col * uBrightness, 1);
            break;
        }
        case 2: {
            outputColor = vec4(wave(gl_FragCoord.xy), 1);
            break;
        }
        case 3: {
            outputColor = vec4(wave(vec2(0.0, gl_FragCoord.y)), 1);
            break;
        }
        case 4: {
            vec3 o = -3.1416 * vec3(0.0, 0.5, 1.0);

            float g = gl_FragCoord.y + uTime;
            vec3 col1 = 0.5 + 0.5 * -sin(g) * cos(g + o);

            outputColor = vec4(col1 * uColor1.rgb, 1);
            break;
        }
    }

    float alpha = 0;

    if(glowThinOutline == 1 && prevColor.a == 0) {
        for(int x = -1; x <= 1; x++) {
            for(int y = -1; y <= 1; y++) {
                if(x != 0 || y != 0) {
                    vec4 offsetColor = texture(Sampler0, uv + vec2(x, y) * uHalfTexelSize);

                    if(offsetColor.a != 0) {
                        color = vec4(outputColor.rgb, 1);
                        return;
                    }
                }
            }
        }
    }

    float i = 0;
    float j = 0;
    int width = int(glowQuality * int(glowRadius));
    int count = int(glowRadius * glowRadius + glowRadius);

    j += sign(texture(Sampler0, uv + vec2(width, 0) * uHalfTexelSize).a);
    j += sign(texture(Sampler0, uv + vec2(-width, 0) * uHalfTexelSize).a);
    j += sign(texture(Sampler0, uv + vec2(0, width) * uHalfTexelSize).a);
    j += sign(texture(Sampler0, uv + vec2(0, -width) * uHalfTexelSize).a);

    j += sign(texture(Sampler0, uv + vec2(width, width) * uHalfTexelSize).a);
    j += sign(texture(Sampler0, uv + vec2(width, -width) * uHalfTexelSize).a);
    j += sign(texture(Sampler0, uv + vec2(-width, width) * uHalfTexelSize).a);
    j += sign(texture(Sampler0, uv + vec2(-width, -width) * uHalfTexelSize).a);

    if(glowRadius > 2 && j == 0.0) {
        if(prevColor.a != 0) {
            color = vec4(outputColor.rgb, outputColor.a * fillAlphaMult);
            return;
        } else {
            discard;
        }
    }

    for(int x = -width; x <= width; x += glowQuality) {
        for(int y = -width; y <= width; y += glowQuality) {
            if (abs(x) == width && abs(y) == width  || abs(x) == width && y == 0 || x == 0 && abs(y) == width || x == 0 && y == 0)
                continue;

            vec4 offsetColor = texture(Sampler0, uv + vec2(x, y) * uHalfTexelSize);

            if(offsetColor.a != 0)
                j++;

            i++;
        }
    }

    alpha = (j / (count * 4));

    if(prevColor.a != 0) {
        alpha = max((1.0 - alpha) * glowAlphaMult, outputColor.a * fillAlphaMult);
    } else {
        alpha *= glowAlphaMult;
    }

    color = vec4(outputColor.rgb, alpha);
}
