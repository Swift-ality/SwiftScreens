#version 330
#extension GL_ARB_separate_shader_objects : require

// SwiftScreens: the game's 26.3 text shader, plus fullscreen screens.
// A screen is a set of item-frame maps that carry a signature in their top row; the server sends them to the one
// player whose screen it is. Those maps are drawn as one picture filling the window, and the pointer follows the
// camera's own turn, every frame. A map may be its own texture or a 128x128 cell of an atlas (ImmediatelyFast):
// the cell is found from which corner of the quad this vertex is, worked out from where it is (the server hangs
// the frames in the block round the eye, the eye at a known spot in it), never from the order vertices come in.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:globals.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
uniform sampler2D Sampler0;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 4) flat out int soScreen;
layout(location = 5) flat out ivec2 soTile;
layout(location = 6) flat out vec2 soPointer;
layout(location = 7) flat out ivec2 soBase;
layout(location = 8) out vec2 soPic;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
const float SO_COLS = @COLS@.0;
const float SO_ROWS = @ROWS@.0;
// Degrees of head turn across the whole picture: 10 + 1.4 * the number in signature pixels 16-23
// (the server sets it so the pointer crosses the window in about as many mouse steps as a desktop pointer).
float so_width_degrees(int n) {
    return 10.0 + 1.4 * float(n);
}

const float SO_FRACTION = @FRACTION@;

// 1 white, 0 black, -1 anything else (map pixels are opaque; glyph atlases are white on clear)
int so_bit(ivec2 base, int x) {
    vec4 c = texelFetch(Sampler0, base + ivec2(x, 0), 0);
    if (c.a < 0.99) return -1;
    float l = dot(c.rgb, vec3(0.3333));
    return l > 0.85 ? 1 : l < 0.15 ? 0 : -1;
}

bool so_signed(ivec2 base) {
    ivec2 size = textureSize(Sampler0, 0);
    if (base.x < 0 || base.y < 0 || base.x + 128 > size.x || base.y + 128 > size.y) return false;
    if (!(so_bit(base, 0) == 1 && so_bit(base, 1) == 0 && so_bit(base, 2) == 1 && so_bit(base, 3) == 1
        && so_bit(base, 4) == 0 && so_bit(base, 5) == 0 && so_bit(base, 6) == 1 && so_bit(base, 7) == 0)) return false;
    for (int i = 8; i < 32; i++) if (so_bit(base, i) < 0) return false;
    return true;
}

int so_byte(ivec2 base, int from) {
    int n = 0;
    for (int i = 0; i < 8; i++) n = n * 2 + so_bit(base, from + i);
    return n;
}

int so_number(ivec2 base, int from) {
    int n = 0;
    for (int i = 0; i < 4; i++) n = n * 2 + so_bit(base, from + i);
    return n;
}

// Where the pointer is, 0..1 across the picture, from the camera's own yaw and pitch: an even speed everywhere.
// The server turns the player to face +Z (yaw 0) when the screen opens, and works out clicks the same way.
vec2 so_pointer(float widthDegrees) {
    vec3 look = -vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);
    float yaw = degrees(atan(-look.x, look.z));
    float pitch = degrees(asin(clamp(-look.y, -1.0, 1.0)));
    // the picture's own shape (the server works clicks out with the same numbers)
    float heightDegrees = min(widthDegrees * (SO_ROWS / SO_COLS), 170.0);
    return clamp(vec2(0.5 + yaw / widthDegrees, 0.5 + pitch / heightDegrees), 0.0, 1.0);
}
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    soScreen = 0;
    soTile = ivec2(0);
    soPointer = vec2(0.0);
    soBase = ivec2(0);
    soPic = vec2(0.0);
    ivec2 size = textureSize(Sampler0, 0);
    // Position is this vertex relative to the camera. The frames face north in the block round the eye, the eye
    // SO_FRACTION into the block along x: the quad's east edge (the picture's left) is the side past the middle.
    float cx = Position.x > 0.5 - SO_FRACTION ? 0.0 : 1.0;
    vec2 corner = vec2(cx, 0.0);
    ivec2 base = ivec2(0);
    // Top or bottom: the eye's height in the block is in the signature (pixels 24-31). Try both rows; the right
    // one is signed and puts this vertex on the side of the middle it really is on.
    for (int row = 0; row < 2; row++) {
        float cy = float(row);
        ivec2 b = ivec2(round(UV0 * vec2(size) - vec2(cx, cy) * 128.0));
        if (!so_signed(b)) continue;
        float eyeFraction = (float(so_byte(b, 24)) + 0.5) / 256.0;
        // relative to the camera the quad's edges are at -e and 1-e; relative to the camera's block (should a
        // backend send that) at whole numbers, 0 and 1: x tells which, as its edges are never whole from the eye
        bool blockRelative = abs(Position.x - round(Position.x)) < 0.01;
        float middle = blockRelative ? 0.5 : 0.5 - eyeFraction;
        float side = Position.y > middle ? 0.0 : 1.0;
        if (side != cy) continue;
        ivec2 tile = ivec2(so_number(b, 8), so_number(b, 12));
        if (float(tile.x) >= SO_COLS || float(tile.y) >= SO_ROWS) continue;
        soScreen = 1;
        soBase = b;
        soTile = tile;
        corner = vec2(cx, cy);
        break;
    }
    if (soScreen == 1) {
        vec2 pic = (vec2(soTile) + corner) / vec2(SO_COLS, SO_ROWS);
        // Keep the picture's shape: as big as fits, in the middle of the window.
        float window = ScreenSize.x / max(ScreenSize.y, 1.0);
        float shape = SO_COLS / SO_ROWS;
        vec2 fit = window > shape ? vec2(shape / window, 1.0) : vec2(1.0, window / shape);
        vec2 ndc = vec2(pic.x * 2.0 - 1.0, 1.0 - pic.y * 2.0) * fit;
        // The outer tiles reach to the window's edges, drawing their edge pixels (the page's background) there.
        if (corner.x == 0.0 && soTile.x == 0) ndc.x = -1.0;
        if (corner.x == 1.0 && float(soTile.x) == SO_COLS - 1.0) ndc.x = 1.0;
        if (corner.y == 0.0 && soTile.y == 0) ndc.y = 1.0;
        if (corner.y == 1.0 && float(soTile.y) == SO_ROWS - 1.0) ndc.y = -1.0;
        // the picture position at this vertex (outside 0..1 on the stretched edges), for the fragment shader
        soPic = vec2((ndc.x / fit.x + 1.0) * 0.5, (1.0 - ndc.y / fit.y) * 0.5);
        // The nearest depth there is, in whatever depth convention the game uses: a point just past the near
        // plane, through the game's own projection.
        vec4 near = ProjMat * vec4(0.0, 0.0, -0.06, 1.0);
        gl_Position = vec4(ndc.x * near.w, ndc.y * near.w, near.z, near.w);
        soPointer = so_pointer(so_width_degrees(so_byte(base, 16)));
    }
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;
}
