package com.saturn_emu.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.database.Cursor;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_VULKAN_DRIVER = 1001;
    private static final int REQUEST_IMPORT_BIOS = 1002;
    private static final int REQUEST_PICK_GAMES_FOLDER = 1003;
    private static final int REQUEST_PICK_APP_FOLDER = 1004;
    static final String PREFS_NAME = "ymir_android";
    static final String PREF_SETUP_COMPLETED = "setup_completed";
    static final String PREF_APP_STORAGE_ROOT = "app_storage_root";
    static final String PREF_BIOS_PATH = "bios_path";
    static final String PREF_GAMES_FOLDER_URI = "games_folder_uri";
    private static final String PREF_GAME_PATH = "game_path";
    private static final String PREF_GAME_DISPLAY_NAME = "game_display_name";
    private static final String PREF_GAME_BEZEL_PREFIX = "game_bezel_uri_";
    private static final String PREF_GAME_IGDB_PREFIX = "game_igdb_json_";
    private static final String PREF_GAME_IGDB_CHECKED_PREFIX = "game_igdb_checked_";
    private static final String PREF_TOUCH_PAD_VISIBLE = "touch_pad_visible";
    private static final String PREF_VIEW_STYLE = "view_style";
    private static final int VIEW_STYLE_GRID_SMALL = 0;
    private static final int VIEW_STYLE_GRID_MEDIUM = 1;
    private static final int VIEW_STYLE_GRID_LARGE = 2;
    private static final int VIEW_STYLE_CAROUSEL = 3;
    private static final String PREF_RENDERER_BACKEND = "renderer_backend";
    private static final String PREF_ASPECT_MODE = "aspect_mode";
    private static final String PREF_BEZEL_VISIBLE = "bezel_visible";
    private static final String PREF_CRT_ENABLED = "crt_enabled";
    private static final String PREF_BEZEL_GITHUB_URL = "bezel_github_url";

    private static final int ASPECT_4_3 = 0;
    private static final int ASPECT_16_9 = 1;
    private static final int MAX_CONCURRENT_IGDB_REQUESTS = 3;
    private static final int MAX_IGDB_LOOKUPS_PER_SCAN = 120;

    private static final int PAD_UP = 0;
    private static final int PAD_DOWN = 1;
    private static final int PAD_LEFT = 2;
    private static final int PAD_RIGHT = 3;
    private static final int PAD_START = 4;
    private static final int PAD_A = 5;
    private static final int PAD_B = 6;
    private static final int PAD_C = 7;
    private static final int PAD_X = 8;
    private static final int PAD_Y = 9;
    private static final int PAD_Z = 10;
    private static final int PAD_L = 11;
    private static final int PAD_R = 12;

    private long nativeHandle;
    private TextView statusView;
    private TextView fpsView;
    private View settingsPanel;
    private View touchPadOverlay;
    private View mapperOverlay;
    private final java.util.LinkedHashMap<Integer, Button> mapperHotspots = new java.util.LinkedHashMap<>();
    private DpadView editorDpad;
    private TextView mapperStatusText;
    private Button padShowHideButton;
    private Button padEditButton;
    private int mapperWaitingForButton = -1;
    private final List<View> padButtons = new ArrayList<>();
    private final List<PadSpec> padSpecs = new ArrayList<>();
    private FrameLayout virtualPadContainer;
    private DpadView dpadView;
    private TextView padEditBanner;
    private boolean padEditMode = false;
    private View playBar;
    private View gameLibraryScreen;
    private FrameLayout rootView;
    private FrameLayout screenContainer;
    private ImageView bezelView;
    private View displaySwitchOverlay;
    private Button rendererButton;
    private Button aspectButton;
    private Button topCrtButton;
    private Button bezelButton;
    private Button crtButton;
    private Spinner rendererSpinner;
    private SharedPreferences prefs;
    private Uri gamesFolderUri;
    private Uri bezelFolderUri;
    private boolean touchPadVisible;
    private int rendererBackend;
    private int aspectMode;
    private boolean bezelVisible;
    private boolean crtEnabled;
    private boolean displaySwitchInProgress;
    private boolean bezelIndexBuilt;
    private boolean setupWizardWaitingForFolder;
    private boolean debugInfoVisible;
    private OnBackInvokedCallback backInvokedCallback;
    private final Map<String, Uri> bezelExactIndex = new HashMap<>();
    private final Map<String, Uri> bezelLooseIndex = new HashMap<>();
    private final List<BezelLibraryEntry> bezelLibrary = new ArrayList<>();
    private final List<GameLibraryEntry> currentGameLibrary = new ArrayList<>();
    private EditText gameLibrarySearch;
    private GridLayout gameLibraryGrid;
    private HorizontalScrollView gameLibraryCarouselScroll;
    private LinearLayout gameLibraryCarousel;
    private TextView gameLibraryStatus;
    private Button gameLibraryViewButton;
    private IgdbService igdbService;
    private int igdbNextIndex;
    private int igdbInFlight;
    private boolean gameLibraryCarouselMode;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hidePlayBarRunnable = () -> {
        if (playBar != null && settingsPanel != null && settingsPanel.getVisibility() != View.VISIBLE) {
            playBar.setVisibility(View.GONE);
        }
    };
    private final Runnable fpsUpdater = new Runnable() {
        @Override
        public void run() {
            if (nativeHandle != 0 && fpsView != null) {
                fpsView.setText(String.format("FPS %.1f", YmirNative.fps(nativeHandle)));
            }
            uiHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (needsSetup()) {
            startSetupWizard();
            finish();
            return;
        }
        touchPadVisible = prefs.getBoolean(PREF_TOUCH_PAD_VISIBLE, false);
        rendererBackend = clampRenderer(prefs.getInt(PREF_RENDERER_BACKEND, 2));
        aspectMode = clampAspect(prefs.getInt(PREF_ASPECT_MODE, ASPECT_4_3));
        bezelVisible = prefs.getBoolean(PREF_BEZEL_VISIBLE, false);
        crtEnabled = prefs.getBoolean(PREF_CRT_ENABLED, false);
        String savedGamesFolder = prefs.getString(PREF_GAMES_FOLDER_URI, "");
        if (!savedGamesFolder.isEmpty()) {
            gamesFolderUri = Uri.parse(savedGamesFolder);
            bezelFolderUri = gamesFolderUri;
        }
        if (aspectMode == ASPECT_16_9) {
            bezelVisible = false;
            crtEnabled = false;
        }
        nativeHandle = YmirNative.create();
        initializeInternalBackupMemory();
        initializeSmpcPersistentState();
        enterImmersiveMode();
        setContentView(createContentView());
        applyDisplayPreferences(false);
        restoreStartupSettings();
        registerBackHandler();
        uiHandler.post(fpsUpdater);
        showPlayBarTemporarily();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
            resizeScreenSurface();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterBackHandler();
        if (nativeHandle != 0) {
            uiHandler.removeCallbacks(fpsUpdater);
            uiHandler.removeCallbacks(hidePlayBarRunnable);
            YmirNative.saveInternalBackupMemory(nativeHandle);
            YmirNative.saveSmpcPersistentState(nativeHandle);
            YmirNative.stop(nativeHandle);
            YmirNative.destroy(nativeHandle);
            nativeHandle = 0;
        }
        super.onDestroy();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (handleBackNavigation()) {
            return;
        }
        super.onBackPressed();
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        backInvokedCallback = () -> {
            if (!handleBackNavigation()) {
                finish();
            }
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
    }

    private void unregisterBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backInvokedCallback == null) {
            return;
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        backInvokedCallback = null;
    }

    private boolean handleBackNavigation() {
        if (mapperOverlay != null && mapperOverlay.getVisibility() == View.VISIBLE) {
            closeMapperOverlay();
            return true;
        }
        if (gameLibraryScreen != null && gameLibraryScreen.getVisibility() == View.VISIBLE) {
            closeGameLibraryScreen();
            return true;
        }
        if (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.setVisibility(View.GONE);
            refreshOverlayPause();
            showPlayBarTemporarily();
            return true;
        }
        return false;
    }

    @Override
    protected void onPause() {
        if (nativeHandle != 0) {
            YmirNative.saveInternalBackupMemory(nativeHandle);
            YmirNative.saveSmpcPersistentState(nativeHandle);
            YmirNative.setAudioMuted(nativeHandle, true);
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nativeHandle != 0) {
            YmirNative.setAudioMuted(nativeHandle, false);
        }
        enterImmersiveMode();
        resizeScreenSurface();
        showPlayBarTemporarily();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            showPlayBarTemporarily();
        }
        return super.dispatchTouchEvent(event);
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(0xFF050607);

        screenContainer = new FrameLayout(this);
        screenContainer.setBackgroundColor(0xFF000000);
        root.addView(screenContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                YmirNative.setSurface(nativeHandle, holder.getSurface());
                updateStatus("Surface attached");
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                YmirNative.setSurface(nativeHandle, holder.getSurface());
                YmirNative.setSurfaceSize(nativeHandle, width, height);
                updateStatus("Surface " + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                YmirNative.clearSurface(nativeHandle);
                updateStatus("Surface detached");
            }
        });
        screenContainer.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        bezelView = new ImageView(this);
        bezelView.setScaleType(ImageView.ScaleType.FIT_XY);
        bezelView.setAdjustViewBounds(false);
        bezelView.setClickable(false);
        bezelView.setFocusable(false);
        bezelView.setVisibility(bezelVisible ? View.VISIBLE : View.GONE);
        root.addView(bezelView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        playBar = bar;
        bar.setGravity(Gravity.CENTER);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackgroundColor(0xAA181B1F);

        fpsView = new TextView(this);
        fpsView.setTextColor(0xFFE8EAED);
        fpsView.setTextSize(12.0f);
        fpsView.setPadding(dp(8), 0, dp(8), 0);
        fpsView.setText("FPS 0.0");
        bar.addView(fpsView);
        rendererButton = makeCompactButton(rendererLabel(), v -> cycleRenderer());
        bar.addView(rendererButton);
        aspectButton = makeCompactButton(aspectLabel(), v -> cycleAspect());
        bar.addView(aspectButton);
        topCrtButton = makeCompactButton(crtLabel(), v -> toggleCrt());
        bar.addView(topCrtButton);
        bezelButton = makeCompactButton(bezelLabel(), v -> toggleBezel());
        bar.addView(bezelButton);
        bar.addView(makeCompactButton("Pad", v -> toggleTouchPad()));
        bar.addView(makeCompactButton("⚙", v -> toggleSettingsPanel()));

        FrameLayout.LayoutParams playBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        playBarParams.setMargins(0, dp(8), dp(8), 0);
        root.addView(bar, playBarParams);

        touchPadOverlay = createTouchPadOverlay();
        touchPadOverlay.setVisibility(touchPadVisible ? View.VISIBLE : View.GONE);
        root.addView(touchPadOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setVisibility(View.GONE);
        settingsScroll.setFillViewport(false);
        settingsScroll.setBackgroundColor(0xEE181B1F);
        settingsPanel = settingsScroll;

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14), dp(12), dp(14), dp(14));
        settingsScroll.addView(controls);

        LinearLayout settingsHeader = new LinearLayout(this);
        settingsHeader.setGravity(Gravity.CENTER_VERTICAL);
        settingsHeader.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Ymir Settings");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16.0f);
        settingsHeader.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));
        settingsHeader.addView(makeCompactButton("X", v -> { settingsPanel.setVisibility(View.GONE); refreshOverlayPause(); }));
        controls.addView(settingsHeader);

        controls.addView(makeSettingsActionCard("↻", "Rerun Setup Wizard", "App folder, BIOS, and games", v -> rerunSetupWizard()));
        controls.addView(makeSettingsActionCard("▦", "Game Library", "Browse cards, bezels, and IGDB", v -> openGameLibrary()));
        controls.addView(makeSettingsActionCard("🎮", "Controller", "Map a gamepad, or set up the touch controls", v -> openControllerScreen()));
        controls.addView(makeSettingsActionCard("◐", "Bezel", "Show or hide game bezels", v -> toggleBezel()));
        controls.addView(makeSettingsActionCard("⇩", "Download Bezels", "Pull Saturn bezel pack from GitHub", v -> showDownloadBezelsDialog()));
        controls.addView(makeSettingsActionCard("⇩", "Import Vulkan Driver", "Load a custom Vulkan driver file", v -> openDriverImport()));
        controls.addView(makeSettingsActionCard("ⓘ", "Debug Info", "Show renderer and core status", v -> toggleDebugInfo()));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFE8EAED);
        statusView.setTextSize(12.0f);
        statusView.setPadding(0, dp(10), 0, 0);
        statusView.setVisibility(View.GONE);
        controls.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(
                dp(360),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.RIGHT);
        root.addView(settingsScroll, settingsParams);

        gameLibraryScreen = createGameLibraryScreen();
        gameLibraryScreen.setVisibility(View.GONE);
        root.addView(gameLibraryScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mapperOverlay = createMapperOverlay();
        mapperOverlay.setVisibility(View.GONE);
        root.addView(mapperOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        displaySwitchOverlay = createDisplaySwitchOverlay();
        displaySwitchOverlay.setVisibility(View.GONE);
        root.addView(displaySwitchOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private View createGameLibraryScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(18), dp(12), dp(18), dp(12));
        screen.setBackgroundColor(0xFA0B0D10);
        screen.setClickable(true);
        screen.setFocusable(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Game Library");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18.0f);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));

        gameLibraryViewButton = makeCompactButton("Carousel", v -> toggleGameLibraryView());
        header.addView(gameLibraryViewButton);
        header.addView(makeCompactButton("Refresh", v -> refreshGameLibraryScreen()));
        header.addView(makeCompactButton("Bezels", v -> showDownloadBezelsDialog()));
        header.addView(makeCompactButton("X", v -> closeGameLibraryScreen()));
        screen.addView(header);

        gameLibrarySearch = new EditText(this);
        gameLibrarySearch.setSingleLine(true);
        gameLibrarySearch.setHint("Search games");
        gameLibrarySearch.setTextColor(0xFFFFFFFF);
        gameLibrarySearch.setHintTextColor(0xFF8C939D);
        gameLibrarySearch.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42));
        searchParams.setMargins(0, dp(10), 0, dp(8));
        screen.addView(gameLibrarySearch, searchParams);

        gameLibraryStatus = new TextView(this);
        gameLibraryStatus.setTextColor(0xFFBAC2CC);
        gameLibraryStatus.setTextSize(12.0f);
        gameLibraryStatus.setPadding(0, 0, 0, dp(8));
        screen.addView(gameLibraryStatus);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        gameLibraryGrid = new GridLayout(this);
        gameLibraryGrid.setColumnCount(4);
        gameLibraryGrid.setUseDefaultMargins(false);
        scroll.addView(gameLibraryGrid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        gameLibraryCarouselScroll = new HorizontalScrollView(this);
        gameLibraryCarouselScroll.setFillViewport(false);
        gameLibraryCarouselScroll.setVisibility(View.GONE);
        gameLibraryCarousel = new LinearLayout(this);
        gameLibraryCarousel.setOrientation(LinearLayout.HORIZONTAL);
        gameLibraryCarousel.setGravity(Gravity.CENTER_VERTICAL);
        gameLibraryCarouselScroll.addView(gameLibraryCarousel, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        screen.addView(gameLibraryCarouselScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        gameLibrarySearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderGameLibraryCards();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        return screen;
    }

    private View createDisplaySwitchOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setBackgroundColor(0x66000000);

        TextView label = new TextView(this);
        label.setText("Applying display...");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(16.0f);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(18), dp(12), dp(18), dp(12));
        label.setBackgroundColor(0xEE181B1F);
        overlay.addView(label, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return overlay;
    }

    // ---- Unified controller + draggable touch pad (ported from 3DO Opera) ----

    private static final int DPAD_ID = -100;
    private static final int DPAD_SIZE_DP = 150;
    private static final float DPAD_DEF_CX = 0.12f, DPAD_DEF_CY = 0.70f;
    private static final int HS_UNASSIGNED = 0, HS_ASSIGNED = 1, HS_SELECTED = 2;

    private static final class PadSpec {
        final int idx; final String label; final int wDp, hDp; final float defCx, defCy;
        PadSpec(int idx, String label, int wDp, int hDp, float defCx, float defCy) {
            this.idx = idx; this.label = label; this.wDp = wDp; this.hDp = hDp;
            this.defCx = defCx; this.defCy = defCy;
        }
    }

    // Individually-tappable buttons (the D-pad is a separate DpadView).
    private static final PadSpec[] PAD_SPECS = {
            new PadSpec(PAD_L, "L", 96, 44, 0.10f, 0.22f),
            new PadSpec(PAD_R, "R", 96, 44, 0.90f, 0.22f),
            new PadSpec(PAD_START, "Start", 96, 40, 0.50f, 0.92f),
            new PadSpec(PAD_X, "X", 58, 58, 0.78f, 0.60f),
            new PadSpec(PAD_Y, "Y", 58, 58, 0.85f, 0.56f),
            new PadSpec(PAD_Z, "Z", 58, 58, 0.92f, 0.52f),
            new PadSpec(PAD_A, "A", 58, 58, 0.78f, 0.80f),
            new PadSpec(PAD_B, "B", 58, 58, 0.85f, 0.76f),
            new PadSpec(PAD_C, "C", 58, 58, 0.92f, 0.72f),
    };

    private float padDragDownX, padDragDownY;
    private int padDragStartLeft, padDragStartTop;

    private View createTouchPadOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(false);
        virtualPadContainer = overlay;

        padButtons.clear();
        padSpecs.clear();
        for (PadSpec spec : PAD_SPECS) {
            Button button = makeVirtualPadButton(spec);
            overlay.addView(button, new FrameLayout.LayoutParams(
                    dp(spec.wDp), dp(spec.hDp), Gravity.TOP | Gravity.LEFT));
            padButtons.add(button);
            padSpecs.add(spec);
        }

        dpadView = new DpadView(this);
        dpadView.setListener((up, down, left, right) -> {
            YmirNative.setPadButton(nativeHandle, PAD_UP, up);
            YmirNative.setPadButton(nativeHandle, PAD_DOWN, down);
            YmirNative.setPadButton(nativeHandle, PAD_LEFT, left);
            YmirNative.setPadButton(nativeHandle, PAD_RIGHT, right);
        });
        PadSpec dpadSpec = new PadSpec(DPAD_ID, "", DPAD_SIZE_DP, DPAD_SIZE_DP, DPAD_DEF_CX, DPAD_DEF_CY);
        dpadView.setOnTouchListener((view, event) -> padEditMode && handlePadDrag(view, event, dpadSpec));
        overlay.addView(dpadView, new FrameLayout.LayoutParams(
                dp(DPAD_SIZE_DP), dp(DPAD_SIZE_DP), Gravity.TOP | Gravity.LEFT));
        padButtons.add(dpadView);
        padSpecs.add(dpadSpec);

        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) positionPadButtons();
        });

        padEditBanner = new TextView(this);
        padEditBanner.setText("Drag buttons to reposition · tap here when done");
        padEditBanner.setTextColor(0xFFEAF0F7);
        padEditBanner.setTextSize(13.0f);
        padEditBanner.setGravity(Gravity.CENTER);
        padEditBanner.setBackgroundColor(0xCC1E66B0);
        padEditBanner.setPadding(dp(12), dp(8), dp(12), dp(8));
        padEditBanner.setClickable(true);
        padEditBanner.setOnClickListener(v -> setPadEditMode(false));
        padEditBanner.setVisibility(View.GONE);
        overlay.addView(padEditBanner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        return overlay;
    }

    private Button makeVirtualPadButton(PadSpec spec) {
        Button button = makeCompactButton(spec.label, null);
        button.setTextColor(0xEEFFFFFF);
        button.setTextSize(spec.hDp >= 54 ? 16.0f : 13.0f);
        button.setBackground(virtualPadButtonBackground(false));
        button.setOnTouchListener((view, event) -> {
            if (padEditMode) return handlePadDrag(view, event, spec);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    YmirNative.setPadButton(nativeHandle, spec.idx, true);
                    view.setBackground(virtualPadButtonBackground(true));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    YmirNative.setPadButton(nativeHandle, spec.idx, false);
                    view.setBackground(virtualPadButtonBackground(false));
                    return true;
                default:
                    return true;
            }
        });
        return button;
    }

    private boolean handlePadDrag(View view, MotionEvent event, PadSpec spec) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                padDragDownX = event.getRawX();
                padDragDownY = event.getRawY();
                padDragStartLeft = lp.leftMargin;
                padDragStartTop = lp.topMargin;
                view.setBackground(virtualPadButtonBackground(true));
                return true;
            case MotionEvent.ACTION_MOVE: {
                int w = virtualPadContainer.getWidth();
                int h = virtualPadContainer.getHeight();
                lp.leftMargin = clamp(padDragStartLeft + (int) (event.getRawX() - padDragDownX), 0, w - view.getWidth());
                lp.topMargin = clamp(padDragStartTop + (int) (event.getRawY() - padDragDownY), 0, h - view.getHeight());
                view.setLayoutParams(lp);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int w = virtualPadContainer.getWidth();
                int h = virtualPadContainer.getHeight();
                if (w > 0 && h > 0) {
                    prefs.edit()
                            .putFloat(padPosKey(spec.idx, "cx"), (lp.leftMargin + view.getWidth() / 2f) / w)
                            .putFloat(padPosKey(spec.idx, "cy"), (lp.topMargin + view.getHeight() / 2f) / h)
                            .apply();
                }
                view.setBackground(virtualPadButtonBackground(false));
                return true;
            }
            default:
                return true;
        }
    }

    private void positionPadButtons() {
        if (virtualPadContainer == null) return;
        int w = virtualPadContainer.getWidth();
        int h = virtualPadContainer.getHeight();
        if (w <= 0 || h <= 0) return;
        for (int i = 0; i < padButtons.size(); i++) {
            View button = padButtons.get(i);
            PadSpec spec = padSpecs.get(i);
            float cx = prefs.getFloat(padPosKey(spec.idx, "cx"), spec.defCx);
            float cy = prefs.getFloat(padPosKey(spec.idx, "cy"), spec.defCy);
            int bw = dp(spec.wDp), bh = dp(spec.hDp);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
            lp.leftMargin = clamp(Math.round(cx * w - bw / 2f), 0, w - bw);
            lp.topMargin = clamp(Math.round(cy * h - bh / 2f), 0, h - bh);
            button.setLayoutParams(lp);
        }
    }

    private static String padPosKey(int idx, String axis) { return "pad_" + axis + "_" + idx; }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void releasePadButtons() {
        if (dpadView != null) dpadView.release();
        for (int b = 0; b < SaturnPadMappingManager.BUTTON_MAX; b++) {
            YmirNative.setPadButton(nativeHandle, b, false);
        }
    }

    private GradientDrawable virtualPadButtonBackground(boolean pressed) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(999));
        d.setColor(pressed ? 0x99C5CBD3 : 0x665F6670);
        d.setStroke(dp(1), pressed ? 0xEEFFFFFF : 0x99D6DADF);
        return d;
    }

    private void togglePadEditMode() {
        if (!touchPadVisible) toggleTouchPad();
        boolean entering = !padEditMode;
        setPadEditMode(entering);
        if (entering && mapperOverlay != null) mapperOverlay.setVisibility(View.GONE);
    }

    private void setPadEditMode(boolean enabled) {
        padEditMode = enabled;
        if (padEditBanner != null) padEditBanner.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (padEditButton != null) padEditButton.setText(enabled ? "Done" : "Edit Layout");
        if (enabled) releasePadButtons();
    }

    private void resetPadLayout() {
        SharedPreferences.Editor e = prefs.edit();
        for (PadSpec spec : PAD_SPECS) {
            e.remove(padPosKey(spec.idx, "cx")).remove(padPosKey(spec.idx, "cy"));
        }
        e.remove(padPosKey(DPAD_ID, "cx")).remove(padPosKey(DPAD_ID, "cy"));
        e.apply();
        positionPadButtons();
        toast("Touch pad layout reset");
    }

    // ---- Controller mapper screen (Saturn pad photo + tappable hotspots) -----

    private View createMapperOverlay() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xFA0B0D10);
        screen.setClickable(true);
        screen.setFocusable(true);
        screen.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("Controller");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18.0f);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(makeCompactButton("X", v -> closeMapperOverlay()));
        screen.addView(header);

        mapperStatusText = new TextView(this);
        mapperStatusText.setTextColor(0xFFC8D0DB);
        mapperStatusText.setTextSize(13.0f);
        mapperStatusText.setPadding(0, dp(6), 0, dp(6));
        screen.addView(mapperStatusText);

        SaturnControllerView diagram = new SaturnControllerView(this);
        mapperHotspots.clear();
        for (SaturnControllerView.Hotspot hs : SaturnControllerView.hotspots()) {
            Button b = makeHotspotButton(hs.shape);
            b.setTag(hs);
            mapperHotspots.put(hs.buttonIndex, b);
            addMapperTouchFeedback(b, hs.buttonIndex);
            diagram.addView(b);
        }
        editorDpad = new DpadView(this);
        editorDpad.setEditorMode(true);
        editorDpad.setTag(SaturnControllerView.dpadRegion());
        editorDpad.setArmTapListener(dir -> {
            int btn = dir == DpadView.UP ? PAD_UP : dir == DpadView.DOWN ? PAD_DOWN
                    : dir == DpadView.LEFT ? PAD_LEFT : PAD_RIGHT;
            mapperWaitingForButton = btn;
            updateMapperWaitingState();
            toast("Press a controller button for " + SaturnPadMappingManager.buttonName(btn));
        });
        diagram.addView(editorDpad);

        FrameLayout diagramHost = new FrameLayout(this);
        diagramHost.addView(diagram, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        screen.addView(diagramHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout touchRow = new LinearLayout(this);
        touchRow.setOrientation(LinearLayout.HORIZONTAL);
        touchRow.setGravity(Gravity.CENTER_VERTICAL);
        touchRow.setPadding(0, dp(8), 0, 0);
        TextView touchLabel = new TextView(this);
        touchLabel.setText("On-screen touch pad");
        touchLabel.setTextColor(0xFFC8D0DB);
        touchLabel.setTextSize(13.0f);
        touchRow.addView(touchLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        padShowHideButton = makeCompactButton(touchPadVisible ? "Hide" : "Show", v -> toggleTouchPad());
        touchRow.addView(padShowHideButton);
        padEditButton = makeCompactButton("Edit Layout", v -> togglePadEditMode());
        touchRow.addView(padEditButton);
        touchRow.addView(makeCompactButton("Reset", v -> resetPadLayout()));
        screen.addView(touchRow);

        refreshMapperLabels();
        return screen;
    }

    private Button makeHotspotButton(SaturnControllerView.Shape shape) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(9.0f);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(hotspotBackground(shape, HS_UNASSIGNED));
        return b;
    }

    private GradientDrawable hotspotBackground(SaturnControllerView.Shape shape, int state) {
        GradientDrawable d = new GradientDrawable();
        if (shape == SaturnControllerView.Shape.PILL) {
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(dp(999));
        } else {
            d.setShape(GradientDrawable.OVAL);
        }
        int fill, line;
        switch (state) {
            case HS_SELECTED: fill = 0x553BA7FF; line = 0xFF3BA7FF; break;
            case HS_ASSIGNED: fill = 0x6632D17A; line = 0xFF32D17A; break;
            default: fill = 0x22FFFFFF; line = 0x55FFFFFF; break;
        }
        d.setColor(fill);
        d.setStroke(dp(state == HS_SELECTED ? 2 : 1), line);
        return d;
    }

    private void addMapperTouchFeedback(final Button button, final int buttonIndex) {
        button.setOnClickListener(v -> {
            mapperWaitingForButton = buttonIndex;
            updateMapperWaitingState();
            toast("Press a controller button for " + SaturnPadMappingManager.buttonName(buttonIndex));
        });
    }

    private void refreshMapperLabels() { updateMapperWaitingState(); }

    private void updateMapperWaitingState() {
        if (mapperStatusText != null) {
            mapperStatusText.setText(mapperWaitingForButton >= 0
                    ? "Press a controller button for " + SaturnPadMappingManager.buttonName(mapperWaitingForButton)
                    : "Tap a button on the pad, then press a controller button to map it.");
        }
        for (java.util.Map.Entry<Integer, Button> e : mapperHotspots.entrySet()) {
            applyHotspotStyle(e.getValue(), e.getKey());
        }
        if (editorDpad != null) {
            editorDpad.setArmState(DpadView.UP, hotspotState(PAD_UP));
            editorDpad.setArmState(DpadView.DOWN, hotspotState(PAD_DOWN));
            editorDpad.setArmState(DpadView.LEFT, hotspotState(PAD_LEFT));
            editorDpad.setArmState(DpadView.RIGHT, hotspotState(PAD_RIGHT));
            editorDpad.invalidate();
        }
    }

    private int hotspotState(int buttonIndex) {
        if (mapperWaitingForButton == buttonIndex) return HS_SELECTED;
        return SaturnPadMappingManager.getMappedKeyCode(this, buttonIndex) > 0 ? HS_ASSIGNED : HS_UNASSIGNED;
    }

    private void applyHotspotStyle(Button button, int buttonIndex) {
        SaturnControllerView.Shape shape = ((SaturnControllerView.Hotspot) button.getTag()).shape;
        int keyCode = SaturnPadMappingManager.getMappedKeyCode(this, buttonIndex);
        button.setBackground(hotspotBackground(shape, hotspotState(buttonIndex)));
        button.setText(keyCode > 0 ? SaturnPadMappingManager.keyName(keyCode) : "");
    }

    private boolean isGameControllerEvent(KeyEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD);
    }

    private void openControllerScreen() {
        if (settingsPanel != null) settingsPanel.setVisibility(View.GONE);
        if (mapperOverlay != null && mapperOverlay.getVisibility() != View.VISIBLE) toggleMapperOverlay();
    }

    private void toggleMapperOverlay() {
        if (mapperOverlay == null) return;
        if (mapperOverlay.getVisibility() != View.VISIBLE) {
            mapperOverlay.setVisibility(View.VISIBLE);
            mapperOverlay.bringToFront();
            refreshMapperLabels();
            refreshOverlayPause();
        } else {
            closeMapperOverlay();
        }
    }

    private void closeMapperOverlay() {
        if (mapperOverlay == null) return;
        mapperWaitingForButton = -1;
        mapperOverlay.setVisibility(View.GONE);
        refreshOverlayPause();
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void placePadButton(FrameLayout overlay, Button button, int gravity,
                                int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity);
        params.setMargins(left, top, right, bottom);
        overlay.addView(button, params);
    }

    private Button makePadButton(String label, int padButton, int width, int height) {
        Button button = makeCompactButton(label, null);
        button.setMinWidth(width);
        button.setMinimumWidth(width);
        button.setMinHeight(height);
        button.setMinimumHeight(height);
        button.setAlpha(0.62f);
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                YmirNative.setPadButton(nativeHandle, padButton, true);
                view.setPressed(true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                YmirNative.setPadButton(nativeHandle, padButton, false);
                view.setPressed(false);
                return true;
            default:
                return true;
            }
        });
        return button;
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setMinHeight(48);
        return button;
    }

    private Button makeCompactButton(String label, View.OnClickListener listener) {
        Button button = makeButton(label, listener);
        button.setTextSize(12.0f);
        button.setMinWidth(dp(64));
        button.setMinimumWidth(dp(64));
        button.setMinHeight(dp(32));
        button.setMinimumHeight(dp(32));
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private View makeSettingsActionCard(String icon, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBackground(0xFF22272E, 0xFF3D4652));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextColor(0xFFFFFFFF);
        iconView.setTextSize(24.0f);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(cardBackground(0xFF303844, 0xFF596474));
        card.addView(iconView, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        textColumn.setPadding(dp(12), 0, 0, 0);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(14.0f);
        titleView.setMaxLines(1);
        textColumn.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(0xFFBAC2CC);
        subtitleView.setTextSize(11.0f);
        subtitleView.setMaxLines(2);
        textColumn.addView(subtitleView);

        card.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76));
        params.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private void toggleSettingsPanel() {
        boolean show = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        refreshOverlayPause();
        showPlayBarTemporarily();
    }

    /** Pause presentation + audio while any settings/config overlay is open. */
    private void refreshOverlayPause() {
        boolean overlay = (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE)
                || (mapperOverlay != null && mapperOverlay.getVisibility() == View.VISIBLE)
                || (gameLibraryScreen != null && gameLibraryScreen.getVisibility() == View.VISIBLE);
        if (nativeHandle != 0) {
            YmirNative.setPresentationPaused(nativeHandle, overlay);
            YmirNative.setAudioMuted(nativeHandle, overlay);
        }
    }

    private void showDownloadBezelsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(12), dp(24), 0);
        layout.setBackgroundColor(0xFF101418);

        TextView hint = new TextView(this);
        hint.setText("Download per-game Saturn bezel PNGs into app storage.");
        hint.setTextColor(0xFFE8EAED);
        hint.setTextSize(13.0f);
        layout.addView(hint);

        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF8C939D);
        input.setText(prefs.getString(PREF_BEZEL_GITHUB_URL, BezelDownloader.DEFAULT_GITHUB_ZIP_URL));
        layout.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Download Bezels")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "GitHub URL is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString(PREF_BEZEL_GITHUB_URL, url).apply();
                    downloadBezelsFromGithub(url);
                })
                .show();
    }

    private void downloadBezelsFromGithub(String url) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(18), dp(24), dp(8));
        layout.setBackgroundColor(0xFF101418);

        TextView status = new TextView(this);
        status.setText("Starting download...");
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(14.0f);
        layout.addView(status);

        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(18));
        progressParams.setMargins(0, dp(14), 0, dp(8));
        layout.addView(progress, progressParams);

        TextView detail = new TextView(this);
        detail.setTextColor(0xFFBAC2CC);
        detail.setTextSize(12.0f);
        layout.addView(detail);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Downloading Bezels")
                .setView(layout)
                .setCancelable(false)
                .create();
        progressDialog.setOnShowListener(dialog -> {
            if (progressDialog.getWindow() != null) {
                progressDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        progressDialog.show();

        new Thread(() -> {
            try {
                BezelDownloader.Result result = BezelDownloader.downloadGithubZip(this, url, (phase, current, total, indeterminate) ->
                        runOnUiThread(() -> updateBezelDownloadProgress(status, detail, progress, phase, current, total, indeterminate)));
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    invalidateBezelIndex();
                    applyBezelForCurrentGame();
                    renderGameLibraryCards();
                    Toast.makeText(this, "Downloaded " + result.pngCount + " bezels", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "Bezel download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "ymir-bezel-download").start();
    }

    private void updateBezelDownloadProgress(TextView status, TextView detail, android.widget.ProgressBar progress,
                                             String phase, long current, long total, boolean indeterminate) {
        status.setText(phase);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate && total > 0) {
            progress.setMax((int) Math.min(total, Integer.MAX_VALUE));
            progress.setProgress((int) Math.min(current, Integer.MAX_VALUE));
            detail.setText(formatProgressValue(current, total));
        } else if (current > 0) {
            detail.setText(formatBytes(current));
        } else {
            detail.setText("Working...");
        }
    }

    private String formatProgressValue(long current, long total) {
        if (total <= 0) {
            return formatBytes(current);
        }
        if (total < 2048) {
            return current + " / " + total;
        }
        return formatBytes(current) + " / " + formatBytes(total);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private void toggleDebugInfo() {
        debugInfoVisible = !debugInfoVisible;
        if (statusView != null) {
            statusView.setVisibility(debugInfoVisible ? View.VISIBLE : View.GONE);
            if (debugInfoVisible) {
                statusView.setText("Debug info\n\n" + YmirNative.status(nativeHandle));
            }
        }
    }

    private void toggleTouchPad() {
        touchPadVisible = !touchPadVisible;
        prefs.edit().putBoolean(PREF_TOUCH_PAD_VISIBLE, touchPadVisible).apply();
        if (!touchPadVisible) {
            if (padEditMode) setPadEditMode(false);
            releasePadButtons();
        }
        touchPadOverlay.setVisibility(touchPadVisible ? View.VISIBLE : View.GONE);
        if (touchPadVisible) touchPadOverlay.post(this::positionPadButtons);
        if (padShowHideButton != null) padShowHideButton.setText(touchPadVisible ? "Hide" : "Show");
    }

    private void showPlayBarTemporarily() {
        if (playBar == null) {
            return;
        }
        playBar.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hidePlayBarRunnable);
        uiHandler.postDelayed(hidePlayBarRunnable, 3000);
    }

    private void applyRendererSelection() {
        if (displaySwitchInProgress) {
            return;
        }
        rendererBackend = clampRenderer(rendererSpinner.getSelectedItemPosition());
        requestDisplaySwitch("Renderer selected: " + rendererSpinner.getSelectedItem().toString(), true);
    }

    private void cycleRenderer() {
        if (displaySwitchInProgress) {
            return;
        }
        rendererBackend = (rendererBackend + 1) % 3;
        requestDisplaySwitch("Renderer selected: " + rendererLabel(), true);
    }

    private void cycleAspect() {
        if (displaySwitchInProgress) {
            return;
        }
        aspectMode = aspectMode == ASPECT_4_3 ? ASPECT_16_9 : ASPECT_4_3;
        if (aspectMode == ASPECT_16_9) {
            bezelVisible = false;
            crtEnabled = false;
        }
        requestDisplaySwitch("Aspect selected: " + aspectLabel(), true);
    }

    private void toggleBezel() {
        if (displaySwitchInProgress) {
            return;
        }
        bezelVisible = !bezelVisible;
        if (bezelVisible) {
            aspectMode = ASPECT_4_3;
        }
        requestDisplaySwitch("Bezel " + (bezelVisible ? "on" : "off"), true);
    }

    private void toggleCrt() {
        if (displaySwitchInProgress) {
            return;
        }
        crtEnabled = !crtEnabled;
        if (crtEnabled) {
            aspectMode = ASPECT_4_3;
        }
        requestDisplaySwitch("CRT " + (crtEnabled ? "on" : "off"), true);
    }

    private void requestDisplaySwitch(String status, boolean persist) {
        displaySwitchInProgress = true;
        setDisplayControlsEnabled(false);
        if (displaySwitchOverlay != null) {
            displaySwitchOverlay.setVisibility(View.VISIBLE);
            displaySwitchOverlay.bringToFront();
        }
        updateStatusBrief(status);
        persistDisplayPreferences(persist);
        updateDisplayButtons();
        resizeScreenSurface();

        final int selectedRenderer = rendererBackend;
        final int selectedAspect = aspectMode;
        final boolean selectedBezel = bezelVisible;
        final boolean selectedCrt = crtEnabled;
        new Thread(() -> {
            YmirNative.setPresentationPaused(nativeHandle, true);
            YmirNative.setRendererBackend(nativeHandle, selectedRenderer);
            YmirNative.setDisplayOptions(nativeHandle, selectedAspect, selectedBezel, selectedCrt, true);
            SystemClock.sleep(250);
            YmirNative.setPresentationPaused(nativeHandle, false);
            uiHandler.postDelayed(this::finishDisplaySwitch, 650);
        }, "YmirDisplaySwitch").start();
    }

    private void finishDisplaySwitch() {
        displaySwitchInProgress = false;
        if (displaySwitchOverlay != null) {
            displaySwitchOverlay.setVisibility(View.GONE);
        }
        setDisplayControlsEnabled(true);
        updateDisplayButtons();
        updateStatusBrief("Renderer ready: " + rendererLabel());
    }

    private void setDisplayControlsEnabled(boolean enabled) {
        if (rendererButton != null) {
            rendererButton.setEnabled(enabled);
        }
        if (aspectButton != null) {
            aspectButton.setEnabled(enabled);
        }
        if (topCrtButton != null) {
            topCrtButton.setEnabled(enabled);
        }
        if (bezelButton != null) {
            bezelButton.setEnabled(enabled);
        }
        if (crtButton != null) {
            crtButton.setEnabled(enabled);
        }
        if (rendererSpinner != null) {
            rendererSpinner.setEnabled(enabled);
        }
    }

    private void applyDisplayPreferences(boolean persist) {
        rendererBackend = clampRenderer(rendererBackend);
        aspectMode = clampAspect(aspectMode);
        if (aspectMode == ASPECT_16_9) {
            bezelVisible = false;
            crtEnabled = false;
        }
        persistDisplayPreferences(persist);
        YmirNative.setRendererBackend(nativeHandle, rendererBackend);
        YmirNative.setDisplayOptions(nativeHandle, aspectMode, bezelVisible, crtEnabled, true);
        if (rendererSpinner != null) {
            rendererSpinner.setSelection(rendererBackend);
        }
        if (bezelView != null) {
            bezelView.setVisibility(bezelVisible ? View.VISIBLE : View.GONE);
            applyBezelForCurrentGame();
            bezelView.invalidate();
        }
        updateDisplayButtons();
        resizeScreenSurface();
    }

    private void persistDisplayPreferences(boolean persist) {
        if (!persist) {
            return;
        }
        prefs.edit()
                .putInt(PREF_RENDERER_BACKEND, rendererBackend)
                .putInt(PREF_ASPECT_MODE, aspectMode)
                .putBoolean(PREF_BEZEL_VISIBLE, bezelVisible)
                .putBoolean(PREF_CRT_ENABLED, crtEnabled)
                .apply();
    }

    private void resizeScreenSurface() {
        if (rootView == null || screenContainer == null) {
            return;
        }
        rootView.post(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            screenContainer.setLayoutParams(params);
            if (bezelView != null) {
                bezelView.invalidate();
            }
        });
    }

    private void updateDisplayButtons() {
        if (rendererButton != null) {
            rendererButton.setText(rendererLabel());
        }
        if (aspectButton != null) {
            aspectButton.setText(aspectLabel());
        }
        if (topCrtButton != null) {
            topCrtButton.setText(crtLabel());
        }
        if (bezelButton != null) {
            bezelButton.setText(bezelLabel());
        }
        if (crtButton != null) {
            crtButton.setText(crtLabel());
        }
        if (rendererSpinner != null && rendererSpinner.getSelectedItemPosition() != rendererBackend) {
            rendererSpinner.setSelection(rendererBackend);
        }
    }

    private int clampRenderer(int value) {
        return value < 0 || value > 2 ? 0 : value;
    }

    private int clampAspect(int value) {
        return value == ASPECT_16_9 ? ASPECT_16_9 : ASPECT_4_3;
    }

    private String rendererLabel() {
        switch (rendererBackend) {
        case 1: return "GL";
        case 2: return "VK";
        default: return "SW";
        }
    }

    private String aspectLabel() {
        return aspectMode == ASPECT_16_9 ? "16:9" : "4:3";
    }

    private String bezelLabel() {
        return bezelVisible ? "Bezel" : "No Bezel";
    }

    private String crtLabel() {
        return crtEnabled ? "CRT" : "No CRT";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void openDriverImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_VULKAN_DRIVER);
    }

    private void openBiosImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_BIOS);
    }

    private void openGamesFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_GAMES_FOLDER);
    }

    private void openAppFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_APP_FOLDER);
    }

    private void rerunSetupWizard() {
        startSetupWizard();
        finish();
    }

    private void startSetupWizard() {
        Intent intent = new Intent(this, SetupWizardActivity.class);
        startActivity(intent);
    }

    private boolean needsSetup() {
        if (!prefs.getBoolean(PREF_SETUP_COMPLETED, false)) {
            return true;
        }

        String biosPath = prefs.getString(PREF_BIOS_PATH, "");
        if (biosPath.isEmpty() || !new File(biosPath).isFile()) {
            return true;
        }

        String gamesFolder = prefs.getString(PREF_GAMES_FOLDER_URI, "");
        if (gamesFolder.isEmpty()) {
            return true;
        }

        String appRoot = prefs.getString(PREF_APP_STORAGE_ROOT, "");
        if (appRoot.isEmpty()) {
            return true;
        }
        return !StoragePathUtils.isWritableDirectory(new File(appRoot));
    }

    private void openGameLibrary() {
        if (gamesFolderUri == null) {
            updateStatus("Select the games folder before opening the library");
            openGamesFolderPicker();
            return;
        }

        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.GONE);
        }
        if (playBar != null) {
            playBar.setVisibility(View.GONE);
        }
        if (gameLibraryScreen != null) {
            gameLibraryScreen.setVisibility(View.VISIBLE);
            gameLibraryScreen.bringToFront();
        }
        if (gameLibrarySearch != null) {
            gameLibrarySearch.setText("");
        }
        refreshOverlayPause();
        refreshGameLibraryScreen();
    }

    private void refreshGameLibraryScreen() {
        if (gameLibraryStatus != null) {
            gameLibraryStatus.setText("Scanning games folder...");
        }
        currentGameLibrary.clear();
        renderGameLibraryCards();

        new Thread(() -> {
            List<GameLibraryEntry> games = scanGameLibrary();
            uiHandler.post(() -> {
                hydrateSavedIgdbMatches(games);
                currentGameLibrary.clear();
                currentGameLibrary.addAll(games);
                renderGameLibraryCards();
                beginIgdbMatching();
                if (games.isEmpty()) {
                    updateStatus("No Saturn disc images found in games folder");
                }
            });
        }, "YmirGameLibraryScan").start();
    }

    private void closeGameLibraryScreen() {
        if (gameLibraryScreen != null) {
            gameLibraryScreen.setVisibility(View.GONE);
        }
        refreshOverlayPause();
        showPlayBarTemporarily();
    }

    private void renderGameLibraryCards() {
        if (gameLibraryGrid == null || gameLibraryCarousel == null || gameLibraryStatus == null) {
            return;
        }
        int style = prefs.getInt(PREF_VIEW_STYLE, VIEW_STYLE_GRID_MEDIUM);
        boolean carousel = style == VIEW_STYLE_CAROUSEL;
        gameLibraryCarouselMode = carousel;
        gameLibraryGrid.removeAllViews();
        gameLibraryCarousel.removeAllViews();
        if (gameLibraryCarouselScroll != null) {
            gameLibraryCarouselScroll.setVisibility(carousel ? View.VISIBLE : View.GONE);
        }
        gameLibraryGrid.setVisibility(carousel ? View.GONE : View.VISIBLE);
        if (!carousel) gameLibraryGrid.setColumnCount(gridColumns(style));
        if (gameLibraryViewButton != null) {
            gameLibraryViewButton.setText(viewStyleName(style));
        }

        String query = gameLibrarySearch == null ? "" : gameLibrarySearch.getText().toString();
        int shown = 0;
        for (GameLibraryEntry game : currentGameLibrary) {
            if (!matchesSearch(displayBaseName(game.displayName), query)) {
                continue;
            }
            if (carousel) {
                gameLibraryCarousel.addView(createGameCard(game, VIEW_STYLE_CAROUSEL));
            } else {
                gameLibraryGrid.addView(createGameCard(game, style));
            }
            shown++;
        }

        if (currentGameLibrary.isEmpty()) {
            gameLibraryStatus.setText("No Saturn disc images found. Put games under Ymir/Games.");
        } else if (shown == currentGameLibrary.size()) {
            gameLibraryStatus.setText(shown + " Saturn games");
        } else {
            gameLibraryStatus.setText(shown + " of " + currentGameLibrary.size() + " Saturn games");
        }
    }

    /** Cycle Small → Medium → Large → Carousel. */
    private void toggleGameLibraryView() {
        int style = (prefs.getInt(PREF_VIEW_STYLE, VIEW_STYLE_GRID_MEDIUM) + 1) % 4;
        prefs.edit().putInt(PREF_VIEW_STYLE, style).apply();
        renderGameLibraryCards();
    }

    private int gridColumns(int style) {
        switch (style) {
            case VIEW_STYLE_GRID_SMALL: return 6;
            case VIEW_STYLE_GRID_LARGE: return 3;
            default: return 4;
        }
    }

    private String viewStyleName(int style) {
        switch (style) {
            case VIEW_STYLE_GRID_SMALL: return "Small";
            case VIEW_STYLE_GRID_LARGE: return "Large";
            case VIEW_STYLE_CAROUSEL: return "Carousel";
            default: return "Medium";
        }
    }

    private View createGameCard(GameLibraryEntry entry, int style) {
        boolean carousel = style == VIEW_STYLE_CAROUSEL;
        int cardW, cardH, coverH;
        switch (style) {
            case VIEW_STYLE_GRID_SMALL:  cardW = 118; cardH = 188; coverH = 132; break;
            case VIEW_STYLE_GRID_LARGE:  cardW = 224; cardH = 336; coverH = 248; break;
            case VIEW_STYLE_CAROUSEL:    cardW = 192; cardH = 300; coverH = 228; break;
            default:                     cardW = 168; cardH = 256; coverH = 184; break; // medium
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBackground(0xFF191D22, 0xFF353B44));
        card.setOnClickListener(v -> showGameDetails(entry));
        card.setOnLongClickListener(v -> {
            showGameDetails(entry);
            return true;
        });
        card.setLongClickable(true);

        FrameLayout coverSlot = new FrameLayout(this);
        coverSlot.setBackground(cardBackground(0xFF262C34, 0xFF404853));
        if (entry.coverBitmap != null) {
            ImageView cover = new ImageView(this);
            cover.setImageBitmap(entry.coverBitmap);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setAdjustViewBounds(false);
            coverSlot.addView(cover, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            TextView coverLabel = new TextView(this);
            coverLabel.setText(entry.igdbLookupStarted ? "IGDB" : "SATURN");
            coverLabel.setTextColor(0xFFB9C2CE);
            coverLabel.setTextSize(12.0f);
            coverLabel.setGravity(Gravity.CENTER);
            coverSlot.addView(coverLabel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        card.addView(coverSlot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(coverH)));

        TextView title = new TextView(this);
        title.setText(entry.igdbGame != null && entry.igdbGame.name != null && !entry.igdbGame.name.isBlank()
                ? entry.igdbGame.name
                : displayBaseName(entry.displayName));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(style == VIEW_STYLE_GRID_SMALL ? 10.0f : 12.0f);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setPadding(0, dp(4), 0, 0);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)));

        TextView detail = new TextView(this);
        detail.setText(gameCardDetail(entry));
        detail.setTextColor(0xFF9AA3AF);
        detail.setTextSize(10.0f);
        detail.setGravity(Gravity.CENTER);
        card.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(16)));

        if (carousel) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(cardW), dp(cardH));
            params.setMargins(dp(8), dp(8), dp(8), dp(8));
            card.setLayoutParams(params);
        } else {
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(cardW);
            params.height = dp(cardH);
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            card.setLayoutParams(params);
        }
        return card;
    }

    private String gameCardDetail(GameLibraryEntry entry) {
        if (entry.igdbGame == null) {
            return fileExtensionLabel(entry.displayName);
        }
        StringBuilder detail = new StringBuilder();
        if (entry.igdbGame.releaseDate != null && !entry.igdbGame.releaseDate.isBlank()) {
            detail.append(entry.igdbGame.releaseDate);
        }
        if (entry.igdbGame.publisher != null && !entry.igdbGame.publisher.isBlank()) {
            if (detail.length() > 0) {
                detail.append(" | ");
            }
            detail.append(entry.igdbGame.publisher);
        }
        return detail.length() == 0 ? fileExtensionLabel(entry.displayName) : detail.toString();
    }

    private void beginIgdbMatching() {
        if (currentGameLibrary.isEmpty()) {
            return;
        }
        if (igdbService == null) {
            igdbService = IgdbService.getInstance(this);
        }
        if (!igdbService.hasCredentials()) {
            gameLibraryStatus.setText(currentGameLibrary.size() + " Saturn games | IGDB credentials missing");
            return;
        }
        igdbNextIndex = 0;
        igdbInFlight = 0;
        pumpIgdbQueue();
    }

    private void pumpIgdbQueue() {
        while (igdbInFlight < MAX_CONCURRENT_IGDB_REQUESTS
                && igdbNextIndex < currentGameLibrary.size()
                && igdbNextIndex < MAX_IGDB_LOOKUPS_PER_SCAN) {
            GameLibraryEntry entry = currentGameLibrary.get(igdbNextIndex++);
            if (entry.igdbGame != null) {
                if (entry.coverBitmap == null && entry.igdbGame.coverUrl != null && !entry.igdbGame.coverUrl.isBlank()) {
                    entry.igdbLookupStarted = true;
                    igdbInFlight++;
                    loadIgdbCover(entry, () -> {
                        igdbInFlight--;
                        renderGameLibraryCards();
                        pumpIgdbQueue();
                    });
                }
                continue;
            }
            if (entry.igdbLookupStarted) {
                continue;
            }
            entry.igdbLookupStarted = true;
            igdbInFlight++;
            searchGameOnIgdb(entry);
        }
    }

    private void searchGameOnIgdb(GameLibraryEntry entry) {
        String queryName = buildIgdbQueryName(entry.displayName);
        igdbService.lookupGame(queryName, game -> {
            entry.igdbGame = game;
            if (game != null) {
                saveIgdbMatch(entry, game);
                igdbService.cacheGame(queryName, game);
            } else {
                markIgdbChecked(entry);
            }
            if (game != null && game.coverUrl != null && !game.coverUrl.isBlank()) {
                loadIgdbCover(entry, () -> {
                    igdbInFlight--;
                    renderGameLibraryCards();
                    pumpIgdbQueue();
                });
            } else {
                igdbInFlight--;
                renderGameLibraryCards();
                pumpIgdbQueue();
            }
        });
    }

    private void loadIgdbCover(GameLibraryEntry entry, Runnable onComplete) {
        if (entry.igdbGame == null || entry.igdbGame.coverUrl == null || entry.igdbGame.coverUrl.isBlank()) {
            onComplete.run();
            return;
        }
        igdbService.loadCover(entry.igdbGame.coverUrl, entry.igdbGame.id, cover -> {
            entry.coverBitmap = cover;
            onComplete.run();
        });
    }

    private void hydrateSavedIgdbMatches(List<GameLibraryEntry> games) {
        if (igdbService == null) {
            igdbService = IgdbService.getInstance(this);
        }
        for (GameLibraryEntry entry : games) {
            String saved = prefs.getString(igdbPrefKey(entry), "");
            IgdbService.IgdbGame game = igdbService.decodeGame(saved);
            if (game != null) {
                entry.igdbGame = game;
                entry.igdbLookupStarted = true;
                igdbService.cacheGame(buildIgdbQueryName(entry.displayName), game);
                continue;
            }
            IgdbService.IgdbGame cached = igdbService.getCachedGame(buildIgdbQueryName(entry.displayName));
            if (cached != null) {
                entry.igdbGame = cached;
                entry.igdbLookupStarted = true;
                saveIgdbMatch(entry, cached);
                continue;
            }
            if (prefs.getBoolean(igdbCheckedPrefKey(entry), false)) {
                entry.igdbLookupStarted = true;
            }
        }
    }

    private void saveIgdbMatch(GameLibraryEntry entry, IgdbService.IgdbGame game) {
        if (igdbService == null || entry == null || game == null) {
            return;
        }
        String encoded = igdbService.encodeGame(game);
        if (encoded.isEmpty()) {
            return;
        }
        prefs.edit()
                .putString(igdbPrefKey(entry), encoded)
                .remove(igdbCheckedPrefKey(entry))
                .apply();
    }

    private void markIgdbChecked(GameLibraryEntry entry) {
        prefs.edit().putBoolean(igdbCheckedPrefKey(entry), true).apply();
    }

    private String igdbPrefKey(GameLibraryEntry entry) {
        String key = normalizeBezelKey(displayBaseName(entry.displayName), false);
        if (key.isEmpty()) {
            key = Integer.toHexString(entry.displayName.hashCode());
        }
        return PREF_GAME_IGDB_PREFIX + key;
    }

    private String igdbCheckedPrefKey(GameLibraryEntry entry) {
        String key = normalizeBezelKey(displayBaseName(entry.displayName), false);
        if (key.isEmpty()) {
            key = Integer.toHexString(entry.displayName.hashCode());
        }
        return PREF_GAME_IGDB_CHECKED_PREFIX + key;
    }

    private String buildIgdbQueryName(String rawName) {
        String value = displayBaseName(rawName).trim();
        int cut = value.length();
        int paren = value.indexOf('(');
        int bracket = value.indexOf('[');
        int brace = value.indexOf('{');
        if (paren >= 0) {
            cut = Math.min(cut, paren);
        }
        if (bracket >= 0) {
            cut = Math.min(cut, bracket);
        }
        if (brace >= 0) {
            cut = Math.min(cut, brace);
        }
        value = value.substring(0, cut).replace('_', ' ').trim();
        value = value.replaceAll("(?i)\\bv\\d+(?:\\.\\d+)*\\b", "").trim();
        return value.isEmpty() ? displayBaseName(rawName) : value;
    }

    private void showGameDetails(GameLibraryEntry entry) {
        if (igdbService == null) {
            igdbService = IgdbService.getInstance(this);
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        int padding = dp(12);
        container.setPadding(padding, padding, padding, padding);
        container.setBackgroundColor(0xF8101418);

        LinearLayout leftColumn = new LinearLayout(this);
        leftColumn.setOrientation(LinearLayout.VERTICAL);
        leftColumn.setPadding(0, 0, dp(10), 0);
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(false);
        leftScroll.setScrollbarFadingEnabled(false);
        leftScroll.addView(leftColumn, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout rightColumn = new LinearLayout(this);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.setPadding(dp(10), 0, 0, 0);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, 0, 0, dp(8));
        Button loadButton = makeCompactButton("Load", null);
        actionRow.addView(loadButton, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));
        Button searchMissingButton = null;
        if (entry.igdbGame == null) {
            searchMissingButton = makeCompactButton("Search IGDB", null);
            LinearLayout.LayoutParams searchMissingParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f);
            searchMissingParams.setMargins(dp(8), 0, 0, 0);
            actionRow.addView(searchMissingButton, searchMissingParams);
        }
        Button matchBezelButton = null;
        if (!hasBezelMatch(entry)) {
            matchBezelButton = makeCompactButton("Match Bezel", null);
            LinearLayout.LayoutParams matchParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f);
            matchParams.setMargins(dp(8), 0, 0, 0);
            actionRow.addView(matchBezelButton, matchParams);
        }
        leftColumn.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView bezelStatus = new TextView(this);
        bezelStatus.setText(describeBezelDiagnostic(entry));
        bezelStatus.setTextColor(0xFFBAC2CC);
        bezelStatus.setTextSize(11.0f);
        bezelStatus.setPadding(0, 0, 0, dp(8));
        leftColumn.addView(bezelStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView details = new TextView(this);
        details.setText(formatIgdbDetails(entry));
        details.setTextColor(0xFFE8EAED);
        details.setTextSize(13.0f);
        details.setPadding(0, 0, 0, dp(10));
        leftColumn.addView(details, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Manual IGDB search");
        search.setText(buildIgdbQueryName(entry.displayName));
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0xFF8C939D);
        search.setSelectAllOnFocus(true);
        leftColumn.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button searchButton = makeCompactButton("↻ Search Saturn IGDB", null);
        leftColumn.addView(searchButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView resultsList = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView text = row.findViewById(android.R.id.text1);
                if (text != null) {
                    text.setTextColor(0xFFE8EAED);
                    text.setTextSize(13.0f);
                }
                row.setBackgroundColor(0xFF101418);
                return row;
            }
        };
        List<IgdbService.IgdbGame> results = new ArrayList<>();
        resultsList.setAdapter(adapter);
        resultsList.setBackgroundColor(0xFF101418);
        resultsList.setCacheColorHint(0xFF101418);
        leftColumn.addView(resultsList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180)));

        TextView mediaTitle = new TextView(this);
        mediaTitle.setText("Gameplay Images");
        mediaTitle.setTextColor(0xFFFFFFFF);
        mediaTitle.setTextSize(13.0f);
        mediaTitle.setPadding(0, 0, 0, dp(6));
        rightColumn.addView(mediaTitle);

        ScrollView mediaScroll = new ScrollView(this);
        mediaScroll.setFillViewport(false);
        LinearLayout mediaStrip = new LinearLayout(this);
        mediaStrip.setOrientation(LinearLayout.VERTICAL);
        mediaScroll.addView(mediaStrip, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        rightColumn.addView(mediaScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));
        populateIgdbMediaStrip(entry, mediaStrip);

        TextView mediaHint = new TextView(this);
        mediaHint.setText("Use IGDB search to refresh missing images");
        mediaHint.setTextColor(0xFFBAC2CC);
        mediaHint.setTextSize(11.0f);
        mediaHint.setPadding(0, dp(8), 0, 0);
        rightColumn.addView(mediaHint);

        container.addView(leftScroll, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.05f));
        container.addView(rightColumn, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0.95f));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(wrapGameDetailsWithBezel(entry, container))
                .setNegativeButton("Close", null)
                .create();

        loadButton.setOnClickListener(v -> {
            dialog.dismiss();
            loadGameFromLibrary(entry);
        });
        searchButton.setOnClickListener(v -> {
            if (!igdbService.hasCredentials()) {
                adapter.clear();
                adapter.add("IGDB credentials missing");
                adapter.notifyDataSetChanged();
                return;
            }
            String query = search.getText().toString().trim();
            adapter.clear();
            adapter.add("Searching Saturn IGDB...");
            adapter.notifyDataSetChanged();
            igdbService.searchGames(query, games -> {
                results.clear();
                adapter.clear();
                if (games.isEmpty()) {
                    adapter.add("No Saturn IGDB matches");
                } else {
                    results.addAll(games);
                    for (IgdbService.IgdbGame game : games) {
                        adapter.add(igdbResultLabel(game));
                    }
                }
                adapter.notifyDataSetChanged();
            });
        });
        Button finalSearchMissingButton = searchMissingButton;
        if (finalSearchMissingButton != null) {
            finalSearchMissingButton.setOnClickListener(v -> searchButton.performClick());
        }
        Button finalMatchBezelButton = matchBezelButton;
        if (finalMatchBezelButton != null) {
            finalMatchBezelButton.setOnClickListener(v -> {
                dialog.dismiss();
                openLibraryBezelSelector(entry);
            });
        }
        dialog.setOnShowListener(d -> {
            search.clearFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                dialog.getWindow().setLayout(
                        Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(980)),
                        Math.min(getResources().getDisplayMetrics().heightPixels - dp(24), dp(640)));
            }
            Button closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (closeButton != null) {
                closeButton.setTextColor(0xFFFFFFFF);
            }
        });

        resultsList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= results.size()) {
                return;
            }
            IgdbService.IgdbGame game = results.get(position);
            entry.igdbGame = game;
            entry.igdbLookupStarted = true;
            saveIgdbMatch(entry, game);
            igdbService.cacheGame(buildIgdbQueryName(entry.displayName), game);
            loadIgdbCover(entry, () -> {
                renderGameLibraryCards();
                populateIgdbMediaStrip(entry, mediaStrip);
                updateStatus("Matched IGDB: " + game.name);
            });
            dialog.dismiss();
        });

        dialog.show();
    }

    private View wrapGameDetailsWithBezel(GameLibraryEntry entry, View content) {
        Uri bezelUri = findBezelUriForGame(entry);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF050607);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);

        if (bezelUri != null) {
            ImageView bezel = new ImageView(this);
            bezel.setImageURI(bezelUri);
            bezel.setScaleType(ImageView.ScaleType.FIT_XY);
            frame.addView(bezel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            contentParams.setMargins(dp(92), dp(18), dp(92), dp(18));
            content.setBackgroundColor(0xEA101418);
        } else {
            TextView noMatch = new TextView(this);
            noMatch.setText(describeBezelDiagnostic(entry));
            noMatch.setTextColor(0xFFFFD166);
            noMatch.setTextSize(12.0f);
            noMatch.setGravity(Gravity.CENTER);
            noMatch.setPadding(dp(10), dp(6), dp(10), dp(6));
            noMatch.setBackgroundColor(0xCC1B2028);
            FrameLayout.LayoutParams noMatchParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP);
            frame.addView(noMatch, noMatchParams);
            contentParams.setMargins(dp(10), dp(38), dp(10), dp(10));
            content.setBackgroundColor(0xF0101418);
        }

        frame.addView(content, contentParams);

        frame.setMinimumHeight(dp(560));
        return frame;
    }

    private Uri findBezelUriForGame(GameLibraryEntry entry) {
        Uri bezelUri = findManualBezelForGame(entry.displayName, "");
        return bezelUri != null ? bezelUri : findBezelForGame(entry.displayName);
    }

    private String describeBezelDiagnostic(GameLibraryEntry entry) {
        Uri bezelUri = findBezelUriForGame(entry);
        String key = normalizeBezelKey(entry.displayName, true);
        if (bezelUri != null) {
            return "Matched bezel: " + displayBaseName(bezelUri.getLastPathSegment()) + " | key " + key;
        }
        int count = ensureBezelIndexBuilt();
        return count > 0
                ? "No bezel match for " + displayBaseName(entry.displayName) + " | key " + key + " | " + count + " indexed"
                : "No downloaded/indexed bezels | key " + key;
    }

    private void populateIgdbMediaStrip(GameLibraryEntry entry, LinearLayout mediaStrip) {
        mediaStrip.removeAllViews();
        if (entry.igdbGame == null) {
            addMediaLabel(mediaStrip, "No IGDB media");
            return;
        }

        List<String> urls = new ArrayList<>();
        urls.addAll(entry.igdbGame.screenshots);
        urls.addAll(entry.igdbGame.artworks);
        if (entry.igdbGame.coverUrl != null && !entry.igdbGame.coverUrl.isBlank()) {
            urls.add(entry.igdbGame.coverUrl);
        }
        if (urls.isEmpty()) {
            addMediaLabel(mediaStrip, "No IGDB media");
            return;
        }

        int max = Math.min(urls.size(), 7);
        for (int i = 0; i < max; i++) {
            String url = urls.get(i);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(cardBackground(0xFF20262D, 0xFF3A424C));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(132));
            params.setMargins(0, 0, 0, dp(8));
            mediaStrip.addView(image, params);
            final int index = i;
            if (url.equals(entry.igdbGame.coverUrl)) {
                igdbService.loadCover(url, entry.igdbGame.id, bitmap -> {
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                        entry.coverBitmap = bitmap;
                        renderGameLibraryCards();
                    }
                });
            } else {
                igdbService.loadMediaImage(url, entry.igdbGame.id + "_" + index, bitmap -> {
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                    }
                });
            }
        }
    }

    private void addMediaLabel(LinearLayout mediaStrip, String label) {
        TextView empty = new TextView(this);
        empty.setText(label);
        empty.setTextColor(0xFF9AA3AF);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(cardBackground(0xFF20262D, 0xFF3A424C));
        mediaStrip.addView(empty, new LinearLayout.LayoutParams(dp(180), dp(108)));
    }

    private boolean hasBezelMatch(GameLibraryEntry entry) {
        return findManualBezelForGame(entry.displayName, "") != null || findBezelForGame(entry.displayName) != null;
    }

    private void openLibraryBezelSelector(GameLibraryEntry entry) {
        int count = ensureBezelIndexBuilt();
        if (count == 0 || bezelLibrary.isEmpty()) {
            updateStatus("No Saturn bezel PNGs found");
            showDownloadBezelsDialog();
            return;
        }
        showSearchableBezelDialog(entry.displayName, "", false, () -> showGameDetails(entry));
    }

    private String formatIgdbDetails(GameLibraryEntry entry) {
        StringBuilder details = new StringBuilder();
        details.append(displayBaseName(entry.displayName));
        details.append("\n").append(describeBezelDiagnostic(entry));
        if (entry.igdbGame == null) {
            details.append("\nIGDB: no match yet");
            return details.toString();
        }

        IgdbService.IgdbGame game = entry.igdbGame;
        details.append("\nIGDB: ").append(emptyFallback(game.name, "Unknown"));
        if (game.releaseDate != null && !game.releaseDate.isBlank()) {
            details.append("\nYear: ").append(game.releaseDate);
        }
        if (game.publisher != null && !game.publisher.isBlank()) {
            details.append("\nPublisher: ").append(game.publisher);
        }
        if (game.summary != null && !game.summary.isBlank()) {
            details.append("\n\n").append(game.summary);
        }
        return details.toString();
    }

    private String igdbResultLabel(IgdbService.IgdbGame game) {
        StringBuilder label = new StringBuilder(emptyFallback(game.name, "Unknown"));
        if (game.releaseDate != null && !game.releaseDate.isBlank()) {
            label.append(" (").append(game.releaseDate).append(")");
        }
        if (game.publisher != null && !game.publisher.isBlank()) {
            label.append(" | ").append(game.publisher);
        }
        return label.toString();
    }

    private String emptyFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String joinStrings(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(value);
        }
        return joined.length() == 0 ? "Sega Saturn" : joined.toString();
    }

    private GradientDrawable cardBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private String fileExtensionLabel(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "DISC";
        }
        return name.substring(dot + 1).toUpperCase(Locale.US);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_GAMES_FOLDER) {
            int flags = data.getFlags();
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            String value = uri.toString();
            gamesFolderUri = uri;
            bezelFolderUri = uri;
            invalidateBezelIndex();
            prefs.edit().putString(PREF_GAMES_FOLDER_URI, value).apply();
            YmirNative.setGamesFolderUri(nativeHandle, value);
            List<GameLibraryEntry> games = scanGameLibrary();
            int count = ensureBezelIndexBuilt();
            StringBuilder status = new StringBuilder("Games folder selected")
                    .append("\nFound ").append(games.size()).append(" Saturn disc images")
                    .append("\nIndexed ").append(count).append(" Saturn bezels");
            String applied = applyBezelForCurrentGame();
            if (!applied.isEmpty()) {
                status.append('\n').append(applied);
                enableBezelIfMatched(applied);
            }
            updateStatus(status.toString());
            if (setupWizardWaitingForFolder) {
                setupWizardWaitingForFolder = false;
                uiHandler.postDelayed(() -> {
                    updateStatusBrief("Setup wizard: select Saturn BIOS");
                    openBiosImport();
                }, 400);
            }
            return;
        }
        if (requestCode == REQUEST_PICK_APP_FOLDER) {
            int flags = data.getFlags();
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            File appRoot = StoragePathUtils.fileForTreeUri(uri);
            if (!StoragePathUtils.isWritableDirectory(appRoot)) {
                updateStatus("Select a writable app folder");
                return;
            }
            prefs.edit().putString(PREF_APP_STORAGE_ROOT, appRoot.getAbsolutePath()).apply();
            updateStatus("App folder selected: " + appRoot.getAbsolutePath());
            return;
        }
        try {
            if (requestCode == REQUEST_IMPORT_BIOS) {
                File biosFile = importFile(uri, "bios", "saturn_bios.bin");
                prefs.edit().putString(PREF_BIOS_PATH, biosFile.getAbsolutePath()).apply();
                StringBuilder status = new StringBuilder(YmirNative.loadBiosFile(nativeHandle, biosFile.getAbsolutePath()));
                String gamePath = prefs.getString(PREF_GAME_PATH, "");
                String gameDisplayName = prefs.getString(PREF_GAME_DISPLAY_NAME, "");
                if (!gamePath.isEmpty() && new File(gamePath).isFile()) {
                    String result = YmirNative.loadGameFile(nativeHandle, gamePath);
                    status.append('\n').append(result);
                    if (result.startsWith("Game loaded")) {
                        String bezelStatus = applyBezelForGame(gameDisplayName, gamePath);
                        status.append('\n').append(bezelStatus);
                        enableBezelIfMatched(bezelStatus);
                        status.append("\nBooting saved game");
                    }
                }
                updateStatus(status.toString());
                YmirNative.start(nativeHandle);
            } else if (requestCode == REQUEST_IMPORT_VULKAN_DRIVER) {
                File driverFile = importFile(uri, "vulkan_drivers", "driver.bin");
                YmirNative.setCustomVulkanDriverPath(nativeHandle, driverFile.getAbsolutePath());
                updateStatus("Imported Vulkan driver: " + driverFile.getName()
                        + "\nSwitch renderer to VK from the top bar to use the Vulkan presenter");
            }
        } catch (Exception e) {
            updateStatus("Import failed: " + e.getMessage());
        }
    }

    private File importFile(Uri uri, String directoryName, String fallbackName) throws Exception {
        File dir = new File(getAppStorageRoot(), directoryName);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + directoryName + " directory");
        }
        String name = queryDisplayName(uri);
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }
        File outFile = new File(dir, sanitizeFileName(name));
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(outFile)) {
            if (input == null) {
                throw new IllegalStateException("Could not open selected file");
            }
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return outFile;
    }

    private List<GameLibraryEntry> scanGameLibrary() {
        List<GameLibraryEntry> games = new ArrayList<>();
        if (gamesFolderUri == null) {
            return games;
        }

        ArrayDeque<GameDirectory> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        try {
            String rootDocumentId = DocumentsContract.getTreeDocumentId(gamesFolderUri);
            Uri rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(gamesFolderUri, rootDocumentId);
            queue.add(new GameDirectory(rootDocumentUri, 0));
        } catch (Exception ignored) {
            return games;
        }

        while (!queue.isEmpty() && games.size() < 1000) {
            GameDirectory directory = queue.removeFirst();
            String parentDocumentId;
            try {
                parentDocumentId = DocumentsContract.getDocumentId(directory.uri);
            } catch (Exception ignored) {
                continue;
            }
            if (!visited.add(parentDocumentId)) {
                continue;
            }

            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(gamesFolderUri, parentDocumentId);
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
                if (cursor == null) {
                    continue;
                }
                while (cursor.moveToNext() && games.size() < 1000) {
                    String documentId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    if (documentId == null || name == null) {
                        continue;
                    }
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(gamesFolderUri, documentId);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        if (directory.depth < 4 && !name.startsWith(".")) {
                            queue.add(new GameDirectory(childUri, directory.depth + 1));
                        }
                    } else if (isSaturnDiscImage(name)) {
                        games.add(new GameLibraryEntry(name, childUri));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        games.sort(Comparator.comparing(entry -> entry.displayName.toLowerCase(Locale.US)));
        // Collapse duplicates that differ only by name sanitisation — playing a
        // game copies it back into this folder under a sanitised filename, which
        // would otherwise show up as a second entry. Region/version variants keep
        // distinct normalized keys, so they are preserved.
        Set<String> seenKeys = new HashSet<>();
        List<GameLibraryEntry> deduped = new ArrayList<>();
        for (GameLibraryEntry entry : games) {
            String key = normalizeBezelKey(entry.displayName, false);
            if (key.isEmpty() || seenKeys.add(key)) {
                deduped.add(entry);
            }
        }
        return deduped;
    }

    private boolean isSaturnDiscImage(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".cue")
                || lower.endsWith(".ccd")
                || lower.endsWith(".chd")
                || lower.endsWith(".iso")
                || lower.endsWith(".mds");
    }

    private void loadGameFromLibrary(GameLibraryEntry entry) {
        try {
            // Play the game where it is — only fall back to copying into app
            // storage when the real path isn't directly readable (e.g. the games
            // live on shared storage we can't access by raw path).
            File realFile = StoragePathUtils.fileForDocumentUri(entry.uri);
            File gameFile = (realFile != null && realFile.canRead())
                    ? realFile
                    : importFile(entry.uri, "games", entry.displayName);
            prefs.edit()
                    .putString(PREF_GAME_PATH, gameFile.getAbsolutePath())
                    .putString(PREF_GAME_DISPLAY_NAME, entry.displayName)
                    .apply();
            // Close overlays and resume the core BEFORE swapping games — reloading
            // the core while presentation is paused (overlay pause) crashes the
            // renderer.
            if (gameLibraryScreen != null) gameLibraryScreen.setVisibility(View.GONE);
            if (settingsPanel != null) settingsPanel.setVisibility(View.GONE);
            if (mapperOverlay != null) mapperOverlay.setVisibility(View.GONE);
            if (nativeHandle != 0) {
                YmirNative.setPresentationPaused(nativeHandle, false);
                YmirNative.setAudioMuted(nativeHandle, false);
            }
            YmirNative.stop(nativeHandle);
            String result = YmirNative.loadGameFile(nativeHandle, gameFile.getAbsolutePath());
            StringBuilder status = new StringBuilder(result);
            if (result.startsWith("Game loaded")) {
                String bezelStatus = applyBezelForGame(entry.displayName, gameFile.getAbsolutePath());
                status.append('\n').append(bezelStatus);
                enableBezelIfMatched(bezelStatus);
            }
            updateStatus(status.toString());
            YmirNative.start(nativeHandle);
        } catch (Exception e) {
            updateStatus("Game load failed: " + e.getMessage());
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        }
        return null;
    }

    private String sanitizeFileName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String applyBezelForCurrentGame() {
        String gamePath = prefs.getString(PREF_GAME_PATH, "");
        String gameDisplayName = prefs.getString(PREF_GAME_DISPLAY_NAME, "");
        if (gameDisplayName.isEmpty() && gamePath.isEmpty()) {
            setDefaultBezelImage();
            return "";
        }
        return applyBezelForGame(gameDisplayName, gamePath);
    }

    private void openManualBezelSelector() {
        String gamePath = prefs.getString(PREF_GAME_PATH, "");
        String gameDisplayName = prefs.getString(PREF_GAME_DISPLAY_NAME, "");
        if (gameDisplayName.isEmpty() && gamePath.isEmpty()) {
            updateStatus("Select a game before selecting a bezel");
            return;
        }
        if (bezelFolderUri == null) {
            updateStatus("Select the app folder before selecting a bezel");
            return;
        }

        int count = ensureBezelIndexBuilt();
        if (count == 0 || bezelLibrary.isEmpty()) {
            updateStatus("No Saturn bezel PNGs found in app folder");
            return;
        }

        showSearchableBezelDialog(gameDisplayName, gamePath, true);
    }

    private void showSearchableBezelDialog(String gameDisplayName, String gamePath, boolean applyNow) {
        showSearchableBezelDialog(gameDisplayName, gamePath, applyNow, null);
    }

    private void showSearchableBezelDialog(String gameDisplayName, String gamePath, boolean applyNow, Runnable onSelected) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        container.setPadding(padding, padding, padding, 0);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search bezels");
        container.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView listView = new ListView(this);
        container.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                new ArrayList<>());
        listView.setAdapter(adapter);

        List<BezelLibraryEntry> filtered = new ArrayList<>();
        Runnable refresh = () -> {
            String query = search.getText().toString();
            filtered.clear();
            adapter.clear();
            for (BezelLibraryEntry entry : bezelLibrary) {
                String label = displayBaseName(entry.displayName);
                if (matchesSearch(label, query)) {
                    filtered.add(entry);
                    adapter.add(label);
                    if (filtered.size() >= 250) {
                        break;
                    }
                }
            }
            adapter.notifyDataSetChanged();
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Select Bezel")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            applyManualBezel(filtered.get(position), gameDisplayName, gamePath, applyNow);
            if (onSelected != null) {
                onSelected.run();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        refresh.run();
        dialog.setOnShowListener(d -> search.requestFocus());
        dialog.show();
    }

    private boolean matchesSearch(String label, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalizedLabel = normalizeBezelKey(label, false);
        String[] terms = query.toLowerCase(Locale.US).split("\\s+");
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            if (!normalizedLabel.contains(normalizeBezelKey(term, false))) {
                return false;
            }
        }
        return true;
    }

    private void applyManualBezel(BezelLibraryEntry entry, String gameDisplayName, String gamePath, boolean applyNow) {
        prefs.edit()
                .putString(manualBezelPrefKey(gameDisplayName, gamePath), entry.uri.toString())
                .apply();
        if (!applyNow) {
            updateStatus("Saved bezel for: " + displayBaseName(gameDisplayName));
            return;
        }
        String result = applyBezelForGame(gameDisplayName, gamePath);
        enableBezelIfMatched(result);
        updateStatus(result);
    }

    private void matchCurrentGameBezel() {
        String result = applyBezelForCurrentGame();
        if (result.isEmpty()) {
            updateStatus("Select a game before matching a bezel");
            return;
        }
        enableBezelIfMatched(result);
        updateStatus(result);
    }

    private String applyBezelForGame(String gameDisplayName, String gamePath) {
        if (bezelView == null) {
            return "";
        }

        String displayName = gameDisplayName;
        if (displayName == null || displayName.isBlank()) {
            displayName = new File(gamePath).getName();
        }
        Uri manualBezelUri = findManualBezelForGame(displayName, gamePath);
        if (manualBezelUri != null && setBezelImageUri(manualBezelUri)) {
            return "Applied selected bezel for: " + displayBaseName(displayName);
        }

        Uri bezelUri = findBezelForGame(displayName);
        if (bezelUri != null && setBezelImageUri(bezelUri)) {
            return "Applied bezel for: " + displayBaseName(displayName);
        }

        setDefaultBezelImage();
        if (bezelFolderUri == null) {
            return "No app folder selected for Saturn bezels";
        }
        return "No matching Saturn bezel for: " + displayBaseName(displayName);
    }

    private void invalidateBezelIndex() {
        bezelIndexBuilt = false;
        bezelExactIndex.clear();
        bezelLooseIndex.clear();
        bezelLibrary.clear();
    }

    private void enableBezelIfMatched(String bezelStatus) {
        if (bezelStatus == null || !bezelStatus.startsWith("Applied bezel")) {
            return;
        }
        if (bezelVisible && aspectMode == ASPECT_4_3) {
            return;
        }
        bezelVisible = true;
        aspectMode = ASPECT_4_3;
        applyDisplayPreferences(true);
    }

    private Uri findBezelForGame(String gameDisplayName) {
        if (gameDisplayName == null || gameDisplayName.isBlank()) {
            return null;
        }
        if (ensureBezelIndexBuilt() == 0) {
            return null;
        }

        String exactKey = normalizeBezelKey(gameDisplayName, false);
        Uri exact = bezelExactIndex.get(exactKey);
        if (exact != null) {
            return exact;
        }

        String looseKey = normalizeBezelKey(gameDisplayName, true);
        return bezelLooseIndex.get(looseKey);
    }

    private Uri findManualBezelForGame(String gameDisplayName, String gamePath) {
        String manualBezel = prefs.getString(manualBezelPrefKey(gameDisplayName, gamePath), "");
        return manualBezel.isEmpty() ? null : Uri.parse(manualBezel);
    }

    private int ensureBezelIndexBuilt() {
        if (bezelIndexBuilt) {
            return bezelExactIndex.size();
        }
        bezelIndexBuilt = true;
        bezelExactIndex.clear();
        bezelLooseIndex.clear();
        bezelLibrary.clear();
        File downloadedRoot = getDownloadedBezelRoot();
        if (downloadedRoot != null && downloadedRoot.isDirectory()) {
            indexBezelImagesFromFileTree(downloadedRoot, 0, new HashSet<>());
        }
        if (bezelFolderUri == null) {
            bezelLibrary.sort(Comparator.comparing(entry -> entry.displayName.toLowerCase(Locale.US)));
            return bezelExactIndex.size();
        }

        File root = fileForTreeUri(bezelFolderUri);
        if (root != null && indexBezelImagesFromIndexFile(new File(root, "Bezels/Saturn"))) {
            bezelLibrary.sort(Comparator.comparing(entry -> entry.displayName.toLowerCase(Locale.US)));
            return bezelExactIndex.size();
        }

        ArrayDeque<BezelDirectory> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        try {
            String rootDocumentId = DocumentsContract.getTreeDocumentId(bezelFolderUri);
            Uri rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(bezelFolderUri, rootDocumentId);
            queue.add(new BezelDirectory(rootDocumentUri, 0));
        } catch (Exception ignored) {
            return 0;
        }

        while (!queue.isEmpty()) {
            BezelDirectory directory = queue.removeFirst();
            String parentDocumentId;
            try {
                parentDocumentId = DocumentsContract.getDocumentId(directory.uri);
            } catch (Exception ignored) {
                continue;
            }
            if (!visited.add(parentDocumentId)) {
                continue;
            }
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(bezelFolderUri, parentDocumentId);
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
                if (cursor == null) {
                    continue;
                }
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    if (documentId == null || name == null) {
                        continue;
                    }
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(bezelFolderUri, documentId);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        if (directory.depth < 6 && shouldTraverseBezelDirectory(name, directory.depth)) {
                            queue.add(new BezelDirectory(childUri, directory.depth + 1));
                        }
                    } else if (name.toLowerCase(Locale.US).endsWith(".png")) {
                        indexBezelImage(name, childUri);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (bezelExactIndex.isEmpty()) {
            if (root != null) {
                indexBezelImagesFromFileTree(root, 0, new HashSet<>());
            }
        }
        bezelLibrary.sort(Comparator.comparing(entry -> entry.displayName.toLowerCase(Locale.US)));
        return bezelExactIndex.size();
    }

    private File getDownloadedBezelRoot() {
        File appStorage = getAppStorageRoot();
        return appStorage == null ? null : new File(appStorage, "bezels/github/latest");
    }

    private boolean indexBezelImagesFromIndexFile(File bezelRoot) {
        File indexFile = new File(bezelRoot, "bezel-index.tsv");
        if (!indexFile.isFile()) {
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(indexFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                String relativePath = parts[0];
                String displayName = parts.length > 1 ? parts[1] : new File(relativePath).getName();
                File image = new File(bezelRoot, relativePath);
                if (image.isFile() && image.getName().toLowerCase(Locale.US).endsWith(".png")) {
                    indexBezelImage(displayName, Uri.fromFile(image));
                }
            }
        } catch (Exception ignored) {
            invalidateBezelIndex();
            bezelIndexBuilt = true;
            return false;
        }
        return !bezelExactIndex.isEmpty();
    }

    private File fileForTreeUri(Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception ignored) {
            return null;
        }
        int colon = documentId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String volume = documentId.substring(0, colon);
        String relativePath = documentId.substring(colon + 1);
        File base = "primary".equalsIgnoreCase(volume)
                ? new File("/storage/emulated/0")
                : new File("/storage/" + volume);
        return relativePath.isEmpty() ? base : new File(base, relativePath);
    }

    private void indexBezelImagesFromFileTree(File directory, int depth, Set<String> visitedPaths) {
        if (directory == null || depth > 6) {
            return;
        }
        String path;
        try {
            path = directory.getCanonicalPath();
        } catch (Exception ignored) {
            path = directory.getAbsolutePath();
        }
        if (!visitedPaths.add(path)) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                if (depth < 6 && shouldTraverseBezelDirectory(name, depth)) {
                    indexBezelImagesFromFileTree(file, depth + 1, visitedPaths);
                }
            } else if (name.toLowerCase(Locale.US).endsWith(".png")) {
                indexBezelImage(name, Uri.fromFile(file));
            }
        }
    }

    private boolean shouldTraverseBezelDirectory(String name, int depth) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.startsWith(".") || lower.equals("config")) {
            return false;
        }
        return depth == 0
                || lower.equals("retroarch")
                || lower.equals("overlay")
                || lower.equals("gamebezels")
                || lower.equals("saturn")
                || lower.equals("0-9")
                || lower.matches("[a-z]")
                || lower.contains("bezel");
    }

    private void indexBezelImage(String name, Uri uri) {
        bezelLibrary.add(new BezelLibraryEntry(name, uri));

        String exactKey = normalizeBezelKey(name, false);
        if (!exactKey.isEmpty()) {
            bezelExactIndex.putIfAbsent(exactKey, uri);
        }

        String looseKey = normalizeBezelKey(name, true);
        if (!looseKey.isEmpty()) {
            bezelLooseIndex.putIfAbsent(looseKey, uri);
        }
    }

    private String normalizeBezelKey(String name, boolean dropQualifiers) {
        String value = displayBaseName(name).replace('_', ' ').toLowerCase(Locale.US);
        if (dropQualifiers) {
            value = value.replaceAll("\\([^)]*\\)", " ");
            value = value.replaceAll("\\[[^]]*\\]", " ");
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private String displayBaseName(String name) {
        if (name == null) {
            return "";
        }
        String value = new File(name).getName();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String manualBezelPrefKey(String gameDisplayName, String gamePath) {
        String value = gameDisplayName == null || gameDisplayName.isBlank()
                ? displayBaseName(gamePath)
                : displayBaseName(gameDisplayName);
        String key = normalizeBezelKey(value, false);
        if (key.isEmpty()) {
            key = Integer.toHexString(value.hashCode());
        }
        return PREF_GAME_BEZEL_PREFIX + key;
    }

    private boolean setBezelImageUri(Uri uri) {
        try (InputStream ignored = getContentResolver().openInputStream(uri)) {
            if (ignored == null) {
                return false;
            }
        } catch (Exception ignored) {
            return false;
        }
        bezelView.setImageURI(uri);
        bezelView.invalidate();
        return true;
    }

    private void setDefaultBezelImage() {
        if (bezelView != null) {
            bezelView.setImageDrawable(null);
            bezelView.invalidate();
        }
    }

    private void updateStatus(String status) {
        if (statusView != null) {
            statusView.setText(status + "\n\n" + YmirNative.status(nativeHandle));
            statusView.setVisibility(debugInfoVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateStatusBrief(String status) {
        if (statusView != null) {
            statusView.setText(status);
            statusView.setVisibility(debugInfoVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void restoreStartupSettings() {
        String biosPath = prefs.getString(PREF_BIOS_PATH, "");
        String gamesFolderUri = prefs.getString(PREF_GAMES_FOLDER_URI, "");
        String gamePath = prefs.getString(PREF_GAME_PATH, "");
        String gameDisplayName = prefs.getString(PREF_GAME_DISPLAY_NAME, "");
        StringBuilder status = new StringBuilder("Core loaded: ").append(YmirNative.coreInfo());
        boolean biosLoaded = false;
        boolean gameWasSet = !gamePath.isEmpty();
        boolean gameLoaded = false;
        if (!biosPath.isEmpty() && new File(biosPath).isFile()) {
            status.append('\n').append(YmirNative.loadBiosFile(nativeHandle, biosPath));
            biosLoaded = true;
        }
        if (!gamesFolderUri.isEmpty()) {
            YmirNative.setGamesFolderUri(nativeHandle, gamesFolderUri);
            this.gamesFolderUri = Uri.parse(gamesFolderUri);
            bezelFolderUri = this.gamesFolderUri;
            invalidateBezelIndex();
            status.append("\nGames folder restored");
        }
        if (biosLoaded && gameWasSet) {
            if (new File(gamePath).isFile()) {
                String result = YmirNative.loadGameFile(nativeHandle, gamePath);
                status.append('\n').append(result);
                gameLoaded = result.startsWith("Game loaded");
                if (gameLoaded) {
                    String bezelStatus = applyBezelForGame(gameDisplayName, gamePath);
                    status.append('\n').append(bezelStatus);
                    enableBezelIfMatched(bezelStatus);
                    status.append("\nBooting saved game");
                }
            } else {
                status.append("\nSaved game missing: ").append(gamePath);
            }
        }
        if (biosLoaded) {
            YmirNative.start(nativeHandle);
            settingsPanel.setVisibility(gameWasSet && !gameLoaded ? View.VISIBLE : View.GONE);
        } else {
            settingsPanel.setVisibility(View.VISIBLE);
            status.append("\nLoad a Saturn BIOS to boot");
        }
        updateStatus(status.toString());
    }

    private void initializeInternalBackupMemory() {
        File dir = new File(getAppStorageRoot(), "backup");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        YmirNative.loadInternalBackupMemory(nativeHandle, new File(dir, "internal.bup").getAbsolutePath());
    }

    private void initializeSmpcPersistentState() {
        File dir = new File(getAppStorageRoot(), "backup");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        YmirNative.loadSmpcPersistentState(nativeHandle, new File(dir, "smpc.bin").getAbsolutePath());
    }

    private File getAppStorageRoot() {
        String storedRoot = prefs == null ? "" : prefs.getString(PREF_APP_STORAGE_ROOT, "");
        File root = storedRoot == null || storedRoot.isEmpty() ? null : new File(storedRoot);
        if (StoragePathUtils.isWritableDirectory(root)) {
            return root;
        }
        File external = getExternalFilesDir(null);
        if (StoragePathUtils.isWritableDirectory(external)) {
            return external;
        }
        return getFilesDir();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Controller mapper: capture the next gamepad press for the waiting button.
        if (mapperWaitingForButton >= 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && isGameControllerEvent(event) && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            SaturnPadMappingManager.assignKeyCode(this, mapperWaitingForButton, event.getKeyCode());
            mapperWaitingForButton = -1;
            refreshMapperLabels();
            return true;
        }
        int padButton = SaturnPadMappingManager.getMappedButtonForKeyCode(this, event.getKeyCode());
        if (padButton >= 0) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
                YmirNative.setPadButton(nativeHandle, padButton, action == KeyEvent.ACTION_DOWN);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            setAxisButton(PAD_LEFT, getCenteredAxis(event, MotionEvent.AXIS_X) < -0.45f);
            setAxisButton(PAD_RIGHT, getCenteredAxis(event, MotionEvent.AXIS_X) > 0.45f);
            setAxisButton(PAD_UP, getCenteredAxis(event, MotionEvent.AXIS_Y) < -0.45f);
            setAxisButton(PAD_DOWN, getCenteredAxis(event, MotionEvent.AXIS_Y) > 0.45f);
            setAxisButton(PAD_L, getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER) > 0.45f);
            setAxisButton(PAD_R, getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER) > 0.45f);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void setAxisButton(int padButton, boolean pressed) {
        YmirNative.setPadButton(nativeHandle, padButton, pressed);
    }

    private float getCenteredAxis(MotionEvent event, int axis) {
        InputDevice device = event.getDevice();
        if (device == null) {
            return event.getAxisValue(axis);
        }
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        float value = event.getAxisValue(axis);
        if (range != null && Math.abs(value) <= range.getFlat()) {
            return 0.0f;
        }
        return value;
    }

    private int mapGamepadKey(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_UP: return PAD_UP;
        case KeyEvent.KEYCODE_DPAD_DOWN: return PAD_DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT: return PAD_LEFT;
        case KeyEvent.KEYCODE_DPAD_RIGHT: return PAD_RIGHT;
        case KeyEvent.KEYCODE_BUTTON_START:
        case KeyEvent.KEYCODE_BUTTON_MODE: return PAD_START;
        case KeyEvent.KEYCODE_BUTTON_A: return PAD_A;
        case KeyEvent.KEYCODE_BUTTON_B: return PAD_B;
        case KeyEvent.KEYCODE_BUTTON_X: return PAD_X;
        case KeyEvent.KEYCODE_BUTTON_Y: return PAD_Y;
        case KeyEvent.KEYCODE_BUTTON_L1: return PAD_L;
        case KeyEvent.KEYCODE_BUTTON_R1: return PAD_R;
        case KeyEvent.KEYCODE_BUTTON_L2: return PAD_Z;
        case KeyEvent.KEYCODE_BUTTON_R2: return PAD_C;
        case KeyEvent.KEYCODE_BUTTON_THUMBL: return PAD_Y;
        case KeyEvent.KEYCODE_BUTTON_THUMBR: return PAD_C;
        default: return -1;
        }
    }

    private static final class BezelDirectory {
        final Uri uri;
        final int depth;

        BezelDirectory(Uri uri, int depth) {
            this.uri = uri;
            this.depth = depth;
        }
    }

    private static final class GameDirectory {
        final Uri uri;
        final int depth;

        GameDirectory(Uri uri, int depth) {
            this.uri = uri;
            this.depth = depth;
        }
    }

    private static final class GameLibraryEntry {
        final String displayName;
        final Uri uri;
        IgdbService.IgdbGame igdbGame;
        Bitmap coverBitmap;
        boolean igdbLookupStarted;

        GameLibraryEntry(String displayName, Uri uri) {
            this.displayName = displayName;
            this.uri = uri;
        }
    }

    private static final class BezelLibraryEntry {
        final String displayName;
        final Uri uri;

        BezelLibraryEntry(String displayName, Uri uri) {
            this.displayName = displayName;
            this.uri = uri;
        }
    }

}
