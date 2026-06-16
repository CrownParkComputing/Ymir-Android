package com.saturn_emu.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/**
 * User-configurable mapping between Android gamepad key codes and the Saturn pad
 * buttons. Button ids match MainActivity's PAD_* constants (UP=0 … R=12).
 * Defaults reproduce the previous hard-coded {@code mapGamepadKey} table.
 */
public final class SaturnPadMappingManager {

    public static final int BTN_UP = 0;
    public static final int BTN_DOWN = 1;
    public static final int BTN_LEFT = 2;
    public static final int BTN_RIGHT = 3;
    public static final int BTN_START = 4;
    public static final int BTN_A = 5;
    public static final int BTN_B = 6;
    public static final int BTN_C = 7;
    public static final int BTN_X = 8;
    public static final int BTN_Y = 9;
    public static final int BTN_Z = 10;
    public static final int BTN_L = 11;
    public static final int BTN_R = 12;

    public static final int BUTTON_MAX = 13;

    private static final String PREFS = "saturn_pad_map";
    private static final String PREFIX = "map_";

    private SaturnPadMappingManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isValidButton(int button) {
        return button >= 0 && button < BUTTON_MAX;
    }

    public static int getMappedKeyCode(Context context, int button) {
        if (!isValidButton(button)) return -1;
        SharedPreferences p = prefs(context);
        String key = PREFIX + button;
        if (p.contains(key)) return p.getInt(key, -1);
        return defaultKeyCode(button);
    }

    public static int getMappedButtonForKeyCode(Context context, int keyCode) {
        for (int b = 0; b < BUTTON_MAX; b++) {
            if (getMappedKeyCode(context, b) == keyCode) return b;
        }
        return -1;
    }

    public static void assignKeyCode(Context context, int button, int keyCode) {
        if (!isValidButton(button) || keyCode <= 0) return;
        SharedPreferences.Editor e = prefs(context).edit();
        // A physical key maps to a single Saturn button: clear it elsewhere.
        for (int b = 0; b < BUTTON_MAX; b++) {
            if (b != button && getMappedKeyCode(context, b) == keyCode) {
                e.putInt(PREFIX + b, -1);
            }
        }
        e.putInt(PREFIX + button, keyCode);
        e.apply();
    }

    static int defaultKeyCode(int button) {
        switch (button) {
            case BTN_UP: return KeyEvent.KEYCODE_DPAD_UP;
            case BTN_DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case BTN_LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case BTN_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case BTN_START: return KeyEvent.KEYCODE_BUTTON_START;
            case BTN_A: return KeyEvent.KEYCODE_BUTTON_A;
            case BTN_B: return KeyEvent.KEYCODE_BUTTON_B;
            case BTN_C: return KeyEvent.KEYCODE_BUTTON_R2;
            case BTN_X: return KeyEvent.KEYCODE_BUTTON_X;
            case BTN_Y: return KeyEvent.KEYCODE_BUTTON_Y;
            case BTN_Z: return KeyEvent.KEYCODE_BUTTON_L2;
            case BTN_L: return KeyEvent.KEYCODE_BUTTON_L1;
            case BTN_R: return KeyEvent.KEYCODE_BUTTON_R1;
            default: return -1;
        }
    }

    public static String buttonName(int button) {
        switch (button) {
            case BTN_UP: return "Up";
            case BTN_DOWN: return "Down";
            case BTN_LEFT: return "Left";
            case BTN_RIGHT: return "Right";
            case BTN_START: return "Start";
            case BTN_A: return "A";
            case BTN_B: return "B";
            case BTN_C: return "C";
            case BTN_X: return "X";
            case BTN_Y: return "Y";
            case BTN_Z: return "Z";
            case BTN_L: return "L";
            case BTN_R: return "R";
            default: return "?";
        }
    }

    public static String keyName(int keyCode) {
        if (keyCode <= 0) return "—";
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_DPAD_UP: return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Right";
            default: break;
        }
        String value = KeyEvent.keyCodeToString(keyCode);
        return value == null ? ("KEY_" + keyCode) : value.replace("KEYCODE_", "");
    }
}
