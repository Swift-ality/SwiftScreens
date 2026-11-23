package com.swiftality.screens;

/** What a screen can ask of whatever shows it. */
public interface ScreenHost {
    /** Shows another screen in the same view. */
    void show(Screen next);

    /** Closes the view. */
    void close();

    /** Plays a sound to the viewer: a Minecraft sound id. */
    void sound(String sound, float volume, float pitch);

    /** Shows a line of text above the viewer's hotbar. */
    void actionBar(String text);
}
