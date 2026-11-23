# SwiftScreens

Fullscreen, clickable menus for **vanilla** Minecraft clients on Paper 26.3. You draw a page with plain Java2D, and
players see it over their whole window, with a mouse pointer that follows their mouse. No client mods and no chest
inventories.

## How it works

- A page is a 1280x768 picture, drawn by your `Screen` subclass with Java2D.
- The picture is cut into 10 x 6 map items, shown on glowing item frames that only the viewer gets (packets only,
  nothing is placed in the world).
- **Shader mode** (the player accepted the pack): SwiftScreens serves a small resource pack with core shaders. The
  client finds the marked maps and draws them over the whole window. It moves the pointer every frame from the
  player's mouse and hides the crosshair.
- **Map mode** (no pack): the camera is locked in front of the maps, and head turns move the pointer.
- Clicks are swings. Sneaking closes the menu. Space is a key your screen can use.

## Use it from your plugin

`plugin.yml`:

```yaml
depend: [SwiftScreens]
```

`build.gradle.kts` (after `./gradlew publishToMavenLocal` in this repo):

```kotlin
repositories { mavenLocal { content { includeGroup("com.swiftality") } } }
dependencies { compileOnly("com.swiftality:swiftscreens:0.2.0") { isTransitive = false } }
```

A screen:

```java
public final class HelloScreen extends Screen {
    private static final int OK = 1;

    @Override
    protected void draw(Graphics2D g) {
        Gfx.fill(g, 0x0B0B0E, 0, 0, W, H);
        g.setFont(Gfx.BIG);
        Gfx.centered(g, "Hello", W / 2, 30, Color.WHITE);
        Gfx.button(g, 540, 360, 200, 40, "OK", hovered(OK), Gfx.Style.GO);
        hit(OK, 540, 360, 200, 40, () -> host.close());
    }
}

SwiftScreens.screens().open(player, new HelloScreen());
```

- `hit(id, x, y, w, h, action)` registers what can be clicked while you draw, so it always matches the picture.
- `hovered(id)` tells you whether the pointer is on it.
- `host.show(next)` switches page, `host.close()` closes, and `host.sound(...)` and `host.actionBar(...)` reach the viewer.
- `draw()` runs on the SwiftScreens render thread and click actions run on the server thread. Read only
  thread-safe state while drawing.
- Override `mayClose()` and `refusedClose()` to keep a menu open until the player has chosen something.
- `ScreenPreview.save(screen, file, px, py)` renders a page to PNG in map colours, with no server. Use it for
  previews and tests.
- When your plugin disables, its open screens close.

## Server owners

Settings are in `plugins/SwiftScreens/config.yml`. They cover the pointer speed and how the resource pack reaches
players: `serve` (built-in web server), `url`, `external` (merge the exported shaders into your own pack) or `off`.
The pack replaces `core/text.vsh`, `core/text.fsh` and `core/position_tex_color.fsh`. Players set their own pointer
speed with `/screens pointer <speed>`.

Before shipping shader changes, run `tools/ShaderCheck.java`. It compiles the shaders with the client's shaderc,
which follows Vulkan rules on 26.3 even under OpenGL.

## License

MIT (see `LICENSE`). The bundled font, Mac's Minecraft, is under the SIL Open Font License
(`src/main/resources/fonts/MacsMinecraft-OFL.txt`).
