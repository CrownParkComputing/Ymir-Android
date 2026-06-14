package com.ymir.android;

import android.view.Surface;

final class YmirNative {
    enum RendererBackend {
        SOFTWARE,
        OPENGL,
        VULKAN
    }

    static {
        System.loadLibrary("ymir_android");
    }

    private YmirNative() {
    }

    static native String coreInfo();

    static native long create();

    static native void destroy(long handle);

    static native void setSurface(long handle, Surface surface);

    static native void setSurfaceSize(long handle, int width, int height);

    static native void clearSurface(long handle);

    static native void setRendererBackend(long handle, int backend);

    static native void setDisplayOptions(long handle, int aspectMode, boolean bezelEnabled, boolean crtEnabled,
                                         boolean cropEnabled);

    static native void setPresentationPaused(long handle, boolean paused);

    static native void setAudioMuted(long handle, boolean muted);

    static native void setCustomVulkanDriverPath(long handle, String path);

    static native String loadBiosFile(long handle, String path);

    static native String loadInternalBackupMemory(long handle, String path);

    static native String saveInternalBackupMemory(long handle);

    static native String loadSmpcPersistentState(long handle, String path);

    static native String saveSmpcPersistentState(long handle);

    static native String loadGameFile(long handle, String path);

    static native void setGamesFolderUri(long handle, String uri);

    static native void setPadButton(long handle, int button, boolean pressed);

    static native void reset(long handle);

    static native boolean runFrame(long handle);

    static native void start(long handle);

    static native void stop(long handle);

    static native double fps(long handle);

    static native String status(long handle);
}
