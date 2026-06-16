package com.saturn_emu.android;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class SetupWizardActivity extends Activity {

    private static final int REQUEST_APP_FOLDER = 1;
    private static final int REQUEST_BIOS_FILE = 2;
    private static final int REQUEST_GAMES_FOLDER = 3;

    private static final int STEP_WELCOME = 0;
    private static final int STEP_APP = 1;
    private static final int STEP_BIOS = 2;
    private static final int STEP_GAMES = 3;

    private LinearLayout stepWelcome;
    private LinearLayout stepApp;
    private LinearLayout stepBios;
    private LinearLayout stepGames;

    private TextView appStatus;
    private TextView biosStatus;
    private TextView gamesStatus;

    private String selectedAppRoot;
    private String selectedBiosPath;
    private String selectedGamesFolder;

    private Button nextButton;
    private Button backButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        selectedAppRoot = prefs.getString(MainActivity.PREF_APP_STORAGE_ROOT, "");
        selectedBiosPath = prefs.getString(MainActivity.PREF_BIOS_PATH, "");
        selectedGamesFolder = prefs.getString(MainActivity.PREF_GAMES_FOLDER_URI, "");

        stepWelcome = findViewById(R.id.step_welcome);
        stepApp = findViewById(R.id.step_app);
        stepBios = findViewById(R.id.step_bios);
        stepGames = findViewById(R.id.step_games);

        appStatus = findViewById(R.id.app_status_text);
        biosStatus = findViewById(R.id.bios_status_text);
        gamesStatus = findViewById(R.id.games_status_text);

        nextButton = findViewById(R.id.next_button);
        backButton = findViewById(R.id.back_button);

        findViewById(R.id.select_app_button).setOnClickListener(v -> openAppFolderPicker());
        findViewById(R.id.select_bios_button).setOnClickListener(v -> openBiosPicker());
        findViewById(R.id.select_games_button).setOnClickListener(v -> openGamesPicker());
        nextButton.setOnClickListener(v -> goToNextStep());
        backButton.setOnClickListener(v -> goToPreviousStep());

        if (selectedAppRoot != null && !selectedAppRoot.isEmpty()) {
            appStatus.setText(getString(R.string.setup_app_selected, selectedAppRoot));
        }
        if (selectedBiosPath != null && !selectedBiosPath.isEmpty()) {
            biosStatus.setText(getString(R.string.setup_bios_selected, selectedBiosPath));
        }
        if (selectedGamesFolder != null && !selectedGamesFolder.isEmpty()) {
            File gamesRoot = StoragePathUtils.fileForTreeUri(Uri.parse(selectedGamesFolder));
            String display = gamesRoot == null ? selectedGamesFolder : gamesRoot.getAbsolutePath();
            gamesStatus.setText(getString(R.string.setup_games_selected, display));
        }

        showStep(STEP_WELCOME);
    }

    private void showStep(int step) {
        stepWelcome.setVisibility(step == STEP_WELCOME ? View.VISIBLE : View.GONE);
        stepApp.setVisibility(step == STEP_APP ? View.VISIBLE : View.GONE);
        stepBios.setVisibility(step == STEP_BIOS ? View.VISIBLE : View.GONE);
        stepGames.setVisibility(step == STEP_GAMES ? View.VISIBLE : View.GONE);
        backButton.setVisibility(step == STEP_WELCOME ? View.GONE : View.VISIBLE);

        switch (step) {
            case STEP_WELCOME:
                nextButton.setText(R.string.setup_next);
                nextButton.setEnabled(true);
                break;
            case STEP_APP:
                nextButton.setText(R.string.setup_next);
                nextButton.setEnabled(selectedAppRoot != null && !selectedAppRoot.isEmpty());
                break;
            case STEP_BIOS:
                nextButton.setText(R.string.setup_next);
                nextButton.setEnabled(selectedBiosPath != null && !selectedBiosPath.isEmpty());
                break;
            case STEP_GAMES:
                nextButton.setText(R.string.setup_finish);
                nextButton.setEnabled(selectedGamesFolder != null && !selectedGamesFolder.isEmpty());
                break;
        }
    }

    private void goToNextStep() {
        if (stepWelcome.getVisibility() == View.VISIBLE) {
            showStep(STEP_APP);
            return;
        }
        if (stepApp.getVisibility() == View.VISIBLE) {
            if (selectedAppRoot == null || selectedAppRoot.isEmpty()) {
                Toast.makeText(this, R.string.setup_app_required, Toast.LENGTH_SHORT).show();
                return;
            }
            showStep(STEP_BIOS);
            return;
        }
        if (stepBios.getVisibility() == View.VISIBLE) {
            if (selectedBiosPath == null || selectedBiosPath.isEmpty()) {
                Toast.makeText(this, R.string.setup_bios_required, Toast.LENGTH_SHORT).show();
                return;
            }
            showStep(STEP_GAMES);
            return;
        }
        if (selectedGamesFolder == null || selectedGamesFolder.isEmpty()) {
            Toast.makeText(this, R.string.setup_games_required, Toast.LENGTH_SHORT).show();
            return;
        }
        saveAndLaunch();
    }

    private void goToPreviousStep() {
        if (stepApp.getVisibility() == View.VISIBLE) {
            showStep(STEP_WELCOME);
        } else if (stepBios.getVisibility() == View.VISIBLE) {
            showStep(STEP_APP);
        } else if (stepGames.getVisibility() == View.VISIBLE) {
            showStep(STEP_BIOS);
        }
    }

    private void openAppFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_APP_FOLDER);
    }

    private void openBiosPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_BIOS_FILE);
    }

    private void openGamesPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAMES_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_APP_FOLDER) {
            int flags = data.getFlags();
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            File root = StoragePathUtils.fileForTreeUri(uri);
            if (!StoragePathUtils.isWritableDirectory(root)) {
                Toast.makeText(this, R.string.setup_app_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            selectedAppRoot = root.getAbsolutePath();
            getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(MainActivity.PREF_APP_STORAGE_ROOT, selectedAppRoot)
                    .apply();
            appStatus.setText(getString(R.string.setup_app_selected, selectedAppRoot));
            nextButton.setEnabled(true);
            return;
        }

        if (requestCode == REQUEST_BIOS_FILE) {
            try {
                File biosFile = importBios(uri);
                selectedBiosPath = biosFile.getAbsolutePath();
                biosStatus.setText(getString(R.string.setup_bios_selected, selectedBiosPath));
                nextButton.setEnabled(true);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.setup_bios_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQUEST_GAMES_FOLDER) {
            int flags = data.getFlags();
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            selectedGamesFolder = uri.toString();
            getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(MainActivity.PREF_GAMES_FOLDER_URI, selectedGamesFolder)
                    .apply();
            File gamesRoot = StoragePathUtils.fileForTreeUri(uri);
            String display = gamesRoot == null ? selectedGamesFolder : gamesRoot.getAbsolutePath();
            gamesStatus.setText(getString(R.string.setup_games_selected, display));
            nextButton.setEnabled(true);
        }
    }

    private File importBios(Uri uri) throws Exception {
        File appRoot = new File(selectedAppRoot);
        File biosDir = new File(appRoot, "bios");
        if (!biosDir.exists() && !biosDir.mkdirs()) {
            throw new IllegalStateException("Could not create bios directory");
        }

        String name = "saturn_bios.bin";
        File outFile = new File(biosDir, name);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(outFile)) {
            if (input == null) {
                throw new IllegalStateException("Could not open selected file");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return outFile;
    }

    private void saveAndLaunch() {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(MainActivity.PREF_SETUP_COMPLETED, true)
                .putString(MainActivity.PREF_BIOS_PATH, selectedBiosPath)
                .putString(MainActivity.PREF_APP_STORAGE_ROOT, selectedAppRoot)
                .putString(MainActivity.PREF_GAMES_FOLDER_URI, selectedGamesFolder)
                .apply();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
