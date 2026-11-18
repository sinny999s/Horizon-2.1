// Sulkan Realistic tone mapping and color response.
// Native adaptation defines helpers locally and fixes reversed smoothstep edges.
#define TM_WHITE_PATH 1.0
#define TM_DARK_DESATURATION 0.25
#define T_VIBRANCE 1.0
float GetLuminance(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float max0(float x) { return max(0.0, x); }
vec3 clamp01(vec3 x) { return clamp(x, 0.0, 1.0); }
void LinearToRGB(inout vec3 c) {
    c = max(c, vec3(0.0));
    c = mix(1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, 12.92 * c, lessThan(c, vec3(0.0031308)));
}
void DoCompTonemap(inout vec3 color) {
    // Lottes tonemap tuned for Sulkan Realistic
    // Lottes 2016, "Advanced Techniques and Optimization of HDR Color Pipelines"
    // http://32ipi028l5q82yhj72224m8j.wpengine.netdna-cdn.com/wp-content/uploads/2016/03/GdcVdrLottes.pdf
    color = TM_EXPOSURE * color;

    float colorMax = max(color.r, max(color.g, color.b));
    float initialLuminance = GetLuminance(color);

    vec3 a      = vec3(TM_CONTRAST); // General Contrast
    vec3 d      = vec3(1.0); // Roll-off control
    vec3 hdrMax = vec3(8.0); // Maximum input brightness
    vec3 midIn  = vec3(0.25); // Input middle gray
    vec3 midOut = vec3(0.25); // Output middle gray

    vec3 a_d = a * d;
    vec3 hdrMaxA = pow(hdrMax, a);
    vec3 hdrMaxAD = pow(hdrMax, a_d);
    vec3 midInA = pow(midIn, a);
    vec3 midInAD = pow(midIn, a_d);
    vec3 HM1 = hdrMaxA * midOut;
    vec3 HM2 = hdrMaxAD - midInAD;

    vec3 b = (-midInA + HM1) / (HM2 * midOut);
    vec3 c = (hdrMaxAD * midInA - HM1 * midInAD) / (HM2 * midOut);

    vec3 colorOut = pow(color, a) / (pow(color, a_d) * b + c);

    LinearToRGB(colorOut);

    // Remove tonemapping from darker colors for better readability
    const float darkLiftStart = 0.1;
    const float darkLiftMix = 0.75;
    float darkLift = (1.0 - smoothstep(0.0, darkLiftStart, initialLuminance));
    vec3 smoothColor = pow(color, vec3(1.0 / 2.2));
    colorOut = mix(colorOut, smoothColor, darkLift * darkLiftMix * max0(0.55 - abs(1.05 - TM_CONTRAST)) / 0.55);

    // Path to White
    const float wpInputCurveStart = 0.0;
    const float wpInputCurveMax = 16.0; // Increase this value to reduce the effect of white path
    float modifiedLuminance = pow(initialLuminance / wpInputCurveMax, 2.0 - TM_WHITE_PATH) * wpInputCurveMax;
    float whitePath = smoothstep(wpInputCurveStart, wpInputCurveMax, modifiedLuminance);
    colorOut = mix(colorOut, vec3(1.0), whitePath);

    // Desaturate dark colors
    const float dpInputCurveStart = 0.1;
    const float dpInputCurveMax = 0.0;
    float desaturatePath = (1.0 - smoothstep(dpInputCurveMax, dpInputCurveStart, initialLuminance));
    colorOut = mix(colorOut, vec3(GetLuminance(colorOut)), desaturatePath * TM_DARK_DESATURATION);

    color = clamp01(colorOut);
}

void DoBSLColorSaturation(inout vec3 color) {
    float saturationFactor = T_SATURATION + 0.07;

    float grayVibrance = (color.r + color.g + color.b) / 3.0;
    float graySaturation = grayVibrance;
    if (saturationFactor < 1.00) graySaturation = dot(color, vec3(0.299, 0.587, 0.114));

    float mn = min(color.r, min(color.g, color.b));
    float mx = max(color.r, max(color.g, color.b));
    float sat = (1.0 - (mx - mn)) * (1.0 - mx) * grayVibrance * 5.0;
    vec3 lightness = vec3((mn + mx) * 0.5);

    color = mix(color, mix(color, lightness, 1.0 - T_VIBRANCE), sat);
    color = mix(color, lightness, (1.0 - lightness) * (2.0 - T_VIBRANCE) / 2.0 * abs(T_VIBRANCE - 1.0));
    color = color * saturationFactor - graySaturation * (saturationFactor - 1.0);
}
