#version 330
#extension GL_ARB_separate_shader_objects : require

// SwiftScreens: the game's 26.3 position_tex_color, which also draws the crosshair. While the camera stands on the
// SwiftScreens spot (a screen is open), the white crosshair in the middle of the window is not drawn.

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
// a copy of globals.glsl
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

bool so_near(float v, float target) {
    return abs(v - target) < 0.0005;
}

bool so_flagged() {
    float a = @FRACTION@;
    float x = fract(CameraOffset.x), z = fract(CameraOffset.z);
    return (so_near(x, a) || so_near(x, 1.0 - a)) && (so_near(z, a) || so_near(z, 1.0 - a));
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    if (so_flagged() && all(greaterThan(color.rgb, vec3(0.99)))
            && length(gl_FragCoord.xy - ScreenSize * 0.5) < ScreenSize.y * 0.035) {
        discard;
    }
    fragColor = color * ColorModulator;
}
