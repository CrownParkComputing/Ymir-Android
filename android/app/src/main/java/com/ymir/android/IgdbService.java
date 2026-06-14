package com.ymir.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class IgdbService {
    private static final int PLATFORM_SATURN_ID = 32;
    private static final String PLATFORM_SATURN_NAME = "Saturn";
    private static final String GAME_FIELDS =
            "name, cover.url, screenshots.url, artworks.url, summary, first_release_date, platforms.name, involved_companies.company.name, involved_companies.publisher";
    private static final String CLIENT_ID = BuildConfig.IGDB_CLIENT_ID;
    private static final String CLIENT_SECRET = BuildConfig.IGDB_CLIENT_SECRET;

    private static IgdbService instance;

    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, IgdbGame> gameCache = new HashMap<>();
    private final Map<Integer, Bitmap> coverCache = new HashMap<>();
    private String accessToken;

    public static final class IgdbGame {
        int id;
        String name;
        String summary;
        String coverUrl;
        String releaseDate;
        String publisher;
        final List<String> platforms = new ArrayList<>();
        final List<String> screenshots = new ArrayList<>();
        final List<String> artworks = new ArrayList<>();
    }

    interface GameCallback {
        void onGameLoaded(IgdbGame game);
    }

    interface GamesCallback {
        void onGamesLoaded(List<IgdbGame> games);
    }

    interface CoverCallback {
        void onCoverLoaded(Bitmap cover);
    }

    static synchronized IgdbService getInstance(Context context) {
        if (instance == null) {
            instance = new IgdbService(context.getApplicationContext());
        }
        return instance;
    }

    private IgdbService(Context context) {
        this.context = context;
        loadGameCacheFromDisk();
    }

    boolean hasCredentials() {
        return CLIENT_ID != null && !CLIENT_ID.isBlank()
                && CLIENT_SECRET != null && !CLIENT_SECRET.isBlank();
    }

    void lookupGame(String gameName, GameCallback callback) {
        String key = cacheKey(gameName);
        IgdbGame cached = gameCache.get(key);
        if (cached != null) {
            mainHandler.post(() -> callback.onGameLoaded(cached));
            return;
        }

        executor.execute(() -> {
            try {
                if (!hasCredentials() || key.isEmpty()) {
                    mainHandler.post(() -> callback.onGameLoaded(null));
                    return;
                }
                List<IgdbGame> games = searchSaturnGamesBlocking(gameName, 10);

                IgdbGame result = games.isEmpty() ? null : games.get(0);
                if (result != null) {
                    cacheGame(gameName, result);
                }
                mainHandler.post(() -> callback.onGameLoaded(result));
            } catch (Exception ignored) {
                mainHandler.post(() -> callback.onGameLoaded(null));
            }
        });
    }

    void searchGames(String query, GamesCallback callback) {
        executor.execute(() -> {
            try {
                if (!hasCredentials() || query == null || query.isBlank()) {
                    mainHandler.post(() -> callback.onGamesLoaded(new ArrayList<>()));
                    return;
                }
                List<IgdbGame> games = searchSaturnGamesBlocking(query, 25);
                for (IgdbGame game : games) {
                    cacheGame(game.name, game);
                }
                mainHandler.post(() -> callback.onGamesLoaded(games));
            } catch (Exception ignored) {
                mainHandler.post(() -> callback.onGamesLoaded(new ArrayList<>()));
            }
        });
    }

    void cacheGame(String key, IgdbGame game) {
        if (key == null || key.isBlank() || game == null) {
            return;
        }
        gameCache.put(cacheKey(key), game);
        if (game.name != null && !game.name.isBlank()) {
            gameCache.put(cacheKey(game.name), game);
        }
        saveGameCacheToDisk();
    }

    IgdbGame getCachedGame(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return gameCache.get(cacheKey(key));
    }

    String encodeGame(IgdbGame game) {
        if (game == null) {
            return "";
        }
        try {
            return cachedGameJson(game).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    IgdbGame decodeGame(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parseCachedGame(new JSONObject(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    void loadCover(String coverUrl, int gameId, CoverCallback callback) {
        if (coverUrl == null || coverUrl.isBlank() || gameId == 0) {
            mainHandler.post(() -> callback.onCoverLoaded(null));
            return;
        }
        Bitmap cached = coverCache.get(gameId);
        if (cached != null) {
            mainHandler.post(() -> callback.onCoverLoaded(cached));
            return;
        }

        executor.execute(() -> {
            try {
                File cacheDir = new File(context.getCacheDir(), "igdb_covers");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    mainHandler.post(() -> callback.onCoverLoaded(null));
                    return;
                }

                File cachedFile = new File(cacheDir, gameId + ".jpg");
                if (cachedFile.isFile()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
                    if (bitmap != null) {
                        coverCache.put(gameId, bitmap);
                        mainHandler.post(() -> callback.onCoverLoaded(bitmap));
                        return;
                    }
                }

                String finalUrl = coverUrl.startsWith("//") ? "https:" + coverUrl : coverUrl;
                finalUrl = finalUrl.replace("t_thumb", "t_cover_big");
                HttpURLConnection conn = (HttpURLConnection) new URL(finalUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                Bitmap bitmap;
                try (InputStream input = conn.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                } finally {
                    conn.disconnect();
                }
                if (bitmap != null) {
                    coverCache.put(gameId, bitmap);
                    try (FileOutputStream output = new FileOutputStream(cachedFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
                    }
                }
                Bitmap result = bitmap;
                mainHandler.post(() -> callback.onCoverLoaded(result));
            } catch (Exception ignored) {
                mainHandler.post(() -> callback.onCoverLoaded(null));
            }
        });
    }

    void loadMediaImage(String imageUrl, String cacheKey, CoverCallback callback) {
        if (imageUrl == null || imageUrl.isBlank() || cacheKey == null || cacheKey.isBlank()) {
            mainHandler.post(() -> callback.onCoverLoaded(null));
            return;
        }
        executor.execute(() -> {
            try {
                File cacheDir = new File(context.getCacheDir(), "igdb_media");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    mainHandler.post(() -> callback.onCoverLoaded(null));
                    return;
                }

                File cachedFile = new File(cacheDir, sanitizeCacheName(cacheKey) + ".jpg");
                if (cachedFile.isFile()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
                    if (bitmap != null) {
                        mainHandler.post(() -> callback.onCoverLoaded(bitmap));
                        return;
                    }
                }

                String finalUrl = imageUrl.startsWith("//") ? "https:" + imageUrl : imageUrl;
                finalUrl = finalUrl.replace("t_thumb", "t_screenshot_big");
                HttpURLConnection conn = (HttpURLConnection) new URL(finalUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                Bitmap bitmap;
                try (InputStream input = conn.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                } finally {
                    conn.disconnect();
                }
                if (bitmap != null) {
                    try (FileOutputStream output = new FileOutputStream(cachedFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
                    }
                }
                Bitmap result = bitmap;
                mainHandler.post(() -> callback.onCoverLoaded(result));
            } catch (Exception ignored) {
                mainHandler.post(() -> callback.onCoverLoaded(null));
            }
        });
    }

    private void ensureAccessToken() throws Exception {
        if (accessToken != null && !accessToken.isBlank()) {
            return;
        }
        URL authUrl = new URL("https://id.twitch.tv/oauth2/token?client_id=" + CLIENT_ID
                + "&client_secret=" + CLIENT_SECRET + "&grant_type=client_credentials");
        HttpURLConnection conn = (HttpURLConnection) authUrl.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
            conn.disconnect();
            throw new IOException("IGDB auth failed");
        }
        JSONObject json = new JSONObject(readStream(conn.getInputStream()));
        accessToken = json.getString("access_token");
        conn.disconnect();
    }

    private String executeIgdbRequest(String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.igdb.com/v4/games").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Client-ID", CLIENT_ID);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try (OutputStream output = conn.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
            conn.disconnect();
            throw new IOException("IGDB request failed");
        }
        String response = readStream(conn.getInputStream());
        conn.disconnect();
        return response;
    }

    private List<IgdbGame> searchSaturnGamesBlocking(String query, int limit) throws Exception {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isEmpty()) {
            return new ArrayList<>();
        }
        ensureAccessToken();
        String escaped = escapeIgdbSearchText(safeQuery);
        String body = "search \"" + escaped + "\"; fields " + GAME_FIELDS
                + "; where platforms = (" + PLATFORM_SATURN_ID + "); limit " + limit + ";";
        return filterLikelySaturnGames(parseGames(executeIgdbRequest(body)));
    }

    private List<IgdbGame> parseGames(String response) {
        List<IgdbGame> games = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(response);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                IgdbGame game = new IgdbGame();
                game.id = item.optInt("id", 0);
                game.name = item.optString("name", "");
                game.summary = item.optString("summary", "");
                if (item.has("cover") && !item.isNull("cover")) {
                    game.coverUrl = item.getJSONObject("cover").optString("url", "");
                }
                appendImageUrls(item.optJSONArray("screenshots"), game.screenshots);
                appendImageUrls(item.optJSONArray("artworks"), game.artworks);
                long releaseTimestamp = item.optLong("first_release_date", 0L);
                if (releaseTimestamp > 0L) {
                    game.releaseDate = new SimpleDateFormat("yyyy", Locale.US)
                            .format(new Date(releaseTimestamp * 1000L));
                }
                JSONArray platforms = item.optJSONArray("platforms");
                if (platforms != null) {
                    for (int p = 0; p < platforms.length(); p++) {
                        String platform = platforms.getJSONObject(p).optString("name", "");
                        if (!platform.isEmpty()) {
                            game.platforms.add(platform);
                        }
                    }
                }
                JSONArray companies = item.optJSONArray("involved_companies");
                if (companies != null) {
                    for (int c = 0; c < companies.length(); c++) {
                        JSONObject company = companies.getJSONObject(c);
                        if (company.optBoolean("publisher", false)
                                && company.has("company") && !company.isNull("company")) {
                            game.publisher = company.getJSONObject("company").optString("name", "");
                            break;
                        }
                    }
                }
                games.add(game);
            }
        } catch (Exception ignored) {
        }
        return games;
    }

    private List<IgdbGame> filterLikelySaturnGames(List<IgdbGame> games) {
        List<IgdbGame> filtered = new ArrayList<>();
        for (IgdbGame game : games) {
            for (String platform : game.platforms) {
                if (platform != null && platform.toLowerCase(Locale.US)
                        .contains(PLATFORM_SATURN_NAME.toLowerCase(Locale.US))) {
                    filtered.add(game);
                    break;
                }
            }
        }
        return filtered;
    }

    private File getGameCacheFile() {
        return new File(context.getCacheDir(), "igdb_saturn_games.json");
    }

    private void loadGameCacheFromDisk() {
        try {
            File cacheFile = getGameCacheFile();
            if (!cacheFile.isFile()) {
                return;
            }
            byte[] data = new byte[(int) cacheFile.length()];
            try (FileInputStream input = new FileInputStream(cacheFile)) {
                if (input.read(data) <= 0) {
                    return;
                }
            }
            JSONArray array = new JSONObject(new String(data, StandardCharsets.UTF_8)).getJSONArray("games");
            for (int i = 0; i < array.length(); i++) {
                IgdbGame game = parseCachedGame(array.getJSONObject(i));
                gameCache.put(cacheKey(game.name), game);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveGameCacheToDisk() {
        executor.execute(() -> {
            try {
                JSONArray array = new JSONArray();
                for (IgdbGame game : gameCache.values()) {
                    array.put(cachedGameJson(game));
                }
                JSONObject root = new JSONObject();
                root.put("games", array);
                try (FileOutputStream output = new FileOutputStream(getGameCacheFile())) {
                    output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private JSONObject cachedGameJson(IgdbGame game) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", game.id);
        item.put("name", game.name);
        item.put("summary", game.summary);
        item.put("coverUrl", game.coverUrl);
        item.put("releaseDate", game.releaseDate);
        item.put("publisher", game.publisher);
        JSONArray platforms = new JSONArray();
        for (String platform : game.platforms) {
            platforms.put(platform);
        }
        item.put("platforms", platforms);
        JSONArray screenshots = new JSONArray();
        for (String screenshot : game.screenshots) {
            screenshots.put(screenshot);
        }
        item.put("screenshots", screenshots);
        JSONArray artworks = new JSONArray();
        for (String artwork : game.artworks) {
            artworks.put(artwork);
        }
        item.put("artworks", artworks);
        return item;
    }

    private IgdbGame parseCachedGame(JSONObject item) throws Exception {
        IgdbGame game = new IgdbGame();
        game.id = item.optInt("id", 0);
        game.name = item.optString("name", "");
        game.summary = item.optString("summary", "");
        game.coverUrl = item.optString("coverUrl", "");
        game.releaseDate = item.optString("releaseDate", "");
        game.publisher = item.optString("publisher", "");
        JSONArray platforms = item.optJSONArray("platforms");
        if (platforms != null) {
            for (int i = 0; i < platforms.length(); i++) {
                game.platforms.add(platforms.getString(i));
            }
        }
        JSONArray screenshots = item.optJSONArray("screenshots");
        if (screenshots != null) {
            for (int i = 0; i < screenshots.length(); i++) {
                game.screenshots.add(screenshots.getString(i));
            }
        }
        JSONArray artworks = item.optJSONArray("artworks");
        if (artworks != null) {
            for (int i = 0; i < artworks.length(); i++) {
                game.artworks.add(artworks.getString(i));
            }
        }
        return game;
    }

    private void appendImageUrls(JSONArray array, List<String> target) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject image = array.optJSONObject(i);
            if (image == null) {
                continue;
            }
            String url = image.optString("url", "");
            if (!url.isBlank()) {
                target.add(url);
            }
        }
    }

    private String readStream(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        byte[] buffer = new byte[8192];
        StringBuilder out = new StringBuilder();
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        input.close();
        return out.toString();
    }

    private String escapeIgdbSearchText(String query) {
        return query.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String cacheKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).trim();
    }

    private String sanitizeCacheName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
