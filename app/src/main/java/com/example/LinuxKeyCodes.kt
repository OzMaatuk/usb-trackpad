package com.example

/**
 * Standard Linux kernel input event codes (<linux/input-event-codes.h>).
 * Sent directly in EVENT_KEY (0x04) packets to /dev/uinput on the Ubuntu host.
 */
object LinuxKeyCodes {
    const val KEY_RESERVED: Short = 0
    const val KEY_ESC: Short = 1
    const val KEY_1: Short = 2
    const val KEY_2: Short = 3
    const val KEY_3: Short = 4
    const val KEY_4: Short = 5
    const val KEY_5: Short = 6
    const val KEY_6: Short = 7
    const val KEY_7: Short = 8
    const val KEY_8: Short = 9
    const val KEY_9: Short = 10
    const val KEY_0: Short = 11
    const val KEY_MINUS: Short = 12
    const val KEY_EQUAL: Short = 13
    const val KEY_BACKSPACE: Short = 14
    const val KEY_TAB: Short = 15

    const val KEY_Q: Short = 16
    const val KEY_W: Short = 17
    const val KEY_E: Short = 18
    const val KEY_R: Short = 19
    const val KEY_T: Short = 20
    const val KEY_Y: Short = 21
    const val KEY_U: Short = 22
    const val KEY_I: Short = 23
    const val KEY_O: Short = 24
    const val KEY_P: Short = 25
    const val KEY_LEFTBRACE: Short = 26
    const val KEY_RIGHTBRACE: Short = 27
    const val KEY_ENTER: Short = 28

    const val KEY_LEFTCTRL: Short = 29
    const val KEY_A: Short = 30
    const val KEY_S: Short = 31
    const val KEY_D: Short = 32
    const val KEY_F: Short = 33
    const val KEY_G: Short = 34
    const val KEY_H: Short = 35
    const val KEY_J: Short = 36
    const val KEY_K: Short = 37
    const val KEY_L: Short = 38
    const val KEY_SEMICOLON: Short = 39
    const val KEY_APOSTROPHE: Short = 40
    const val KEY_GRAVE: Short = 41

    const val KEY_LEFTSHIFT: Short = 42
    const val KEY_BACKSLASH: Short = 43
    const val KEY_Z: Short = 44
    const val KEY_X: Short = 45
    const val KEY_C: Short = 46
    const val KEY_V: Short = 47
    const val KEY_B: Short = 48
    const val KEY_N: Short = 49
    const val KEY_M: Short = 50
    const val KEY_COMMA: Short = 51
    const val KEY_DOT: Short = 52
    const val KEY_SLASH: Short = 53
    const val KEY_RIGHTSHIFT: Short = 54

    const val KEY_LEFTALT: Short = 56
    const val KEY_SPACE: Short = 57
    const val KEY_CAPSLOCK: Short = 58

    const val KEY_F1: Short = 59
    const val KEY_F2: Short = 60
    const val KEY_F3: Short = 61
    const val KEY_F4: Short = 62
    const val KEY_F5: Short = 63
    const val KEY_F6: Short = 64
    const val KEY_F7: Short = 65
    const val KEY_F8: Short = 66
    const val KEY_F9: Short = 67
    const val KEY_F10: Short = 68
    const val KEY_F11: Short = 87
    const val KEY_F12: Short = 88

    const val KEY_RIGHTCTRL: Short = 97
    const val KEY_SYSRQ: Short = 99      // Print Screen
    const val KEY_RIGHTALT: Short = 100
    const val KEY_HOME: Short = 102
    const val KEY_UP: Short = 103
    const val KEY_PAGEUP: Short = 104
    const val KEY_LEFT: Short = 105
    const val KEY_RIGHT: Short = 106
    const val KEY_END: Short = 107
    const val KEY_DOWN: Short = 108
    const val KEY_PAGEDOWN: Short = 109
    const val KEY_INSERT: Short = 110
    const val KEY_DELETE: Short = 111

    // Media & Audio Keys
    const val KEY_MUTE: Short = 113
    const val KEY_VOLUMEDOWN: Short = 114
    const val KEY_VOLUMEUP: Short = 115
    const val KEY_POWER: Short = 116     // Shutdown / Power action
    const val KEY_LEFTMETA: Short = 125  // Super / Windows / Command key
    const val KEY_SLEEP: Short = 142     // System Suspend / Sleep
    const val KEY_PLAYPAUSE: Short = 164

    // Atomic Modifier Bitmask (passed in val2 for combo clicks like Super+Space or sticky latch)
    const val MOD_NONE: Short = 0
    const val MOD_CTRL: Short = 0x01
    const val MOD_SHIFT: Short = 0x02
    const val MOD_ALT: Short = 0x04
    const val MOD_SUPER: Short = 0x08

    // Client-side Virtual Keys (handled by keyboard engine)
    const val VKEY_FN: Short = -1
    const val VKEY_LANG_SWITCH: Short = -2  // Atomic Super + Space
    const val VKEY_CLOSE_WIN: Short = -3    // Atomic Alt + F4
}
