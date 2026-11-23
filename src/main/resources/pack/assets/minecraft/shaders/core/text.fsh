#version 330
#extension GL_ARB_separate_shader_objects : require

// SwiftScreens: the game's 26.3 text shader, plus fullscreen screens (see text.vsh).

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 4) flat in int soScreen;
layout(location = 5) flat in ivec2 soTile;
layout(location = 6) flat in vec2 soPointer;
layout(location = 7) flat in ivec2 soBase;
layout(location = 8) in vec2 soPic;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

    #ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif

    return color;
}

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
const float SO_COLS = @COLS@.0;
const float SO_ROWS = @ROWS@.0;
// The hand pointer, 13 x 14 cells of 2 picture pixels; bit i is column i. Hot spot: column 4.5, row 0.
const int SO_EDGE[14] = int[14](48, 72, 72, 456, 1608, 2638, 4105, 4097, 4098, 4100, 2052, 2056, 2056, 2032);
const int SO_FILL[14] = int[14](0, 48, 48, 48, 432, 1456, 4086, 4094, 4092, 4088, 2040, 2032, 2032, 0);

// 0 nothing, 1 outline, 2 fill: the pointer at picture pixel [pic].
int so_hand(vec2 pic) {
    vec2 tip = soPointer * vec2(SO_COLS * 128.0, SO_ROWS * 128.0);
    vec2 cell = (pic - tip) / 2.0 + vec2(4.5, 0.0);
    int cx = int(floor(cell.x)), cy = int(floor(cell.y));
    if (cx < 0 || cx > 12 || cy < 0 || cy > 13) return 0;
    if (((SO_EDGE[cy] >> cx) & 1) != 0) return 1;
    if (((SO_FILL[cy] >> cx) & 1) != 0) return 2;
    return 0;
}

vec4 so_screen_color() {
    // where in the whole picture this pixel is (past its edges, the edge pixel), then where in this map
    vec2 size = vec2(SO_COLS * 128.0, SO_ROWS * 128.0);
    vec2 picPx = soPic * size;
    vec2 local = clamp(clamp(picPx, vec2(0.0), size - 0.001) - vec2(soTile) * 128.0, 0.0, 127.999);
    ivec2 tex = ivec2(local);
    // the signature row is data, not picture: show the row under it instead
    if (tex.y == 0 && tex.x < 64) tex.y = 1;
    vec4 c = texelFetch(Sampler0, soBase + tex, 0);
    int h = so_hand(picPx);
    if (h == 1) c = vec4(0.0, 0.0, 0.0, 1.0);
    else if (h == 2) c = vec4(1.0, 1.0, 1.0, 1.0);
    // map colour 0 is clear: a see-through page shows the world there
    c.a = c.a < 0.1 ? 0.0 : 1.0;
    return c;
}
#endif

void main() {
    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    if (soScreen == 1) {
        vec4 c = so_screen_color();
        if (c.a < 0.5) discard;
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, 1.0);
        #else
        #ifdef OIT_ACCUMULATE
        c = sampleColorForAccumulation(c);
        #endif
        fragColor = c;
        #endif
        return;
    }
    #endif

    #ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
    #else
    vec4 texColor = texture(Sampler0, texCoord0);
    #endif

    vec4 color = texColor * vertexColor * ColorModulator;

    if (color.a < 0.1) {
        discard;
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
