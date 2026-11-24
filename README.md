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
dependencies { compileOnly("com.swiftality:swiftscreens:0.3.0") { isTransitive = false } }
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
- `hit(id, x, y, w, h, left, right)` gives a region a separate right-click action. Right clicks work with an empty
  hand: while a menu is open, the hotbar holds invisible client-only items, and the real inventory comes back on close.
- Override `text(String line)` to get what the viewer types in chat while the menu is open, for search boxes.
- Start `draw()` with `Gfx.background(g)`. With `screen.background: false` (the default), the page stays see-through
  and the world shows around your panels.
- `SwiftScreens.faces().face(uuid)` gives a player's 8x8 face, or null while it loads. It's safe to call from
  `draw()`, and open menus redraw when a face arrives.
- Override `mayClose()` and `refusedClose()` to keep a menu open until the player has chosen something.
- `ScreenPreview.save(screen, file, px, py)` renders a page to PNG in map colours, with no server. Use it for
  previews and tests.
- When your plugin disables, its open screens close.

## Screens in the air

A screen can also hang in the world for one player, who keeps walking, jumping and looking around. They aim with
the crosshair: the server casts a ray from their eye every tick, and the pointer hand sits where it meets the
picture. Blocks in between stop the ray.

```java
PlacedScreen board = SwiftScreens.screens().place(player, new HelloScreen());     // in front of them, facing them
SwiftScreens.screens().place(player, screen, location, BlockFace.SOUTH);         // middle near location, facing south
board.close();                                    // or host.close() from the screen; closePlaced(player) for all
```

- It's the same `Screen` class: `hit`, `hovered`, `host.show`, `host.sound` and `host.close` all work.
- Left click clicks and right click right-clicks. While the player aims at the picture, nothing behind it is broken,
  hit or used. With an empty hand, the selected slot holds an invisible item on the client only, so a right click
  still reaches the server.
- It's 10 x 6 blocks (one map per block), facing north, south, east or west. Item frames can't turn any other way.
  The maps carry no shader signature, so it needs no resource pack and the pack never takes it fullscreen.
- Clear pixels are see-through (draw with `Gfx.background(g)` for a solid page).
- It's taken down when the player goes further than `placed.max-distance` from it, changes world or leaves.
  Chat and sneak stay normal, and only fullscreen screens use them.
- A player can have up to 16 placed screens. The nearest one in their line of sight gets the pointer. While a
  fullscreen screen is open, placed screens ignore input.
- `/screens demo` (admin) places a test screen, and `/screens demo clear` takes all of yours down.

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
