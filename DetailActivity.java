package com.cinedor.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_ITEM = "item";
    private static final int REQ_STORAGE = 41;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private Item item;
    
    // সার্ভার থেকে আসা স্ট্রিম এবং ডাউনলোড লিংক সংরক্ষণের জন্য ক্লাস ও লিস্ট
    public static class MediaLink {
        public String name;
        public String url;
        public MediaLink(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private final List<MediaLink> serverStreams = new ArrayList<>();
    private final List<MediaLink> serverDownloads = new ArrayList<>();
    
    private boolean sourcesChecked;
    private String trailerKey = "", watchLink = "", watchNames = "";
    private MediaLink pendingDownload;

    private TextView title, meta, overview, showMore, credits, status;
    private ProgressBar progress;
    private ImageView listIcon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        item = Item.fromJson(String.valueOf(getIntent().getStringExtra(EXTRA_ITEM)));
        if (item == null) { finish(); return; }

        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        overview = findViewById(R.id.overview);
        showMore = findViewById(R.id.showMore);
        credits = findViewById(R.id.credits);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        status.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 10)));

        ImageView back = findViewById(R.id.back);
        back.setBackground(Ui.round(0x99000000, Ui.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        ImageView play = findViewById(R.id.play);
        play.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 60)));
        play.setOnClickListener(v -> onPlay());
        findViewById(R.id.shade).setBackground(Ui.fade(0x00000000, Ui.BG));

        ImageLoader.load(findViewById(R.id.backdrop), item.backdrop.isEmpty() ? item.poster : item.backdrop);
        title.setText(item.title);
        setOverview(item.overview);
        meta.setText(metaLine(item.year, "", "", item.rating));

        LinearLayout actions = findViewById(R.id.actions);
        listIcon = action(actions, Store.isFavorite(this, item) ? R.drawable.ic_check : R.drawable.ic_add, "My List", v -> {
            boolean added = Store.toggleFavorite(this, item);
            listIcon.setImageResource(added ? R.drawable.ic_check : R.drawable.ic_add);
            Toast.makeText(this, added ? "Added to My List" : "Removed from My List", Toast.LENGTH_SHORT).show();
        });
        action(actions, R.drawable.ic_download, "Download", v -> onDownload());
        action(actions, R.drawable.ic_movie, "Trailer", v -> openTrailer());
        action(actions, R.drawable.ic_share, "Share", v -> share());

        showMore.setOnClickListener(v -> {
            boolean expand = overview.getMaxLines() <= 4;
            overview.setMaxLines(expand ? 200 : 4);
            showMore.setText(expand ? "Show less" : "Show more");
        });

        if (Item.ARCHIVE.equals(item.type)) loadArchive(item.id); else loadTmdb();
    }

    private void loadTmdb() {
        boolean movie = Item.MOVIE.equals(item.type);
        String path = item.type + "/" + item.id + "?append_to_response=credits,videos,recommendations,watch/providers,"
                + (movie ? "release_dates" : "content_ratings");
        io.execute(() -> {
            try {
                JSONObject d = Tmdb.get(path);
                main.post(() -> { if (!isFinishing()) showTmdb(d, movie); });
            } catch (Exception e) {
                main.post(() -> {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    sourcesChecked = true;
                    Toast.makeText(this, "Can't load details. Check your internet connection.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showTmdb(JSONObject d, boolean movie) {
        String name = d.optString(movie ? "title" : "name", item.title);
        title.setText(name);
        setOverview(d.optString("overview", item.overview));

        String runtime = "";
        if (movie) {
            int min = d.optInt("runtime");
            if (min > 0) runtime = (min / 60 > 0 ? (min / 60) + "h " : "") + (min % 60) + "m";
        } else {
            int s = d.optInt("number_of_seasons");
            if (s > 0) runtime = s + (s == 1 ? " Season" : " Seasons");
        }
        meta.setText(metaLine(item.year, certification(d, movie), runtime, d.optDouble("vote_average", item.rating)));

        StringBuilder cr = new StringBuilder();
        JSONArray genres = d.optJSONArray("genres");
        if (genres != null) {
            for (int i = 0; i < genres.length(); i++) {
                cr.append(i > 0 ? ", " : "").append(genres.optJSONObject(i).optString("name"));
            }
        }
        JSONObject creditsObj = d.optJSONObject("credits");
        String director = "";
        if (movie && creditsObj != null) {
            JSONArray crew = creditsObj.optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                if ("Director".equals(crew.optJSONObject(i).optString("job"))) {
                    director = "Director: " + crew.optJSONObject(i).optString("name");
                    break;
                }
            }
        } else if (!movie) {
            JSONArray by = d.optJSONArray("created_by");
            if (by != null && by.length() > 0) director = "Creator: " + by.optJSONObject(0).optString("name");
        }
        if (!director.isEmpty()) cr.append(cr.length() > 0 ? "\n" : "").append(director);
        credits.setText(cr);

        JSONObject videos = d.optJSONObject("videos");
        JSONArray vr = videos != null ? videos.optJSONArray("results") : null;
        for (int i = 0; vr != null && i < vr.length(); i++) {
            JSONObject v = vr.optJSONObject(i);
            if (!"YouTube".equals(v.optString("site"))) continue;
            if ("Trailer".equals(v.optString("type"))) { trailerKey = v.optString("key"); break; }
            if (trailerKey.isEmpty()) trailerKey = v.optString("key");
        }

        JSONObject wp = d.optJSONObject("watch/providers");
        JSONObject regions = wp != null ? wp.optJSONObject("results") : null;
        if (regions != null) {
            for (String region : new String[]{"BD", "IN", "US"}) {
                JSONObject r = regions.optJSONObject(region);
                if (r == null) continue;
                watchLink = r.optString("link");
                StringBuilder names = new StringBuilder();
                for (String kind : new String[]{"flatrate", "free", "ads", "rent", "buy"}) {
                    JSONArray ps = r.optJSONArray(kind);
                    for (int i = 0; ps != null && i < ps.length() && names.length() < 60; i++) {
                        String n = ps.optJSONObject(i).optString("provider_name");
                        if (names.indexOf(n) < 0) names.append(names.length() > 0 ? ", " : "").append(n);
                    }
                }
                watchNames = names.toString();
                if (!watchNames.isEmpty()) break;
            }
        }

        showCast(creditsObj != null ? creditsObj.optJSONArray("cast") : null);

        JSONObject rec = d.optJSONObject("recommendations");
        List<Item> more = Tmdb.parse(rec != null ? rec.optJSONArray("results") : null, item.type);
        if (!more.isEmpty()) {
            findViewById(R.id.moreTitle).setVisibility(View.VISIBLE);
            RecyclerView grid = findViewById(R.id.more);
            grid.setLayoutManager(new GridLayoutManager(this, 3));
            PosterAdapter a = new PosterAdapter(PosterAdapter.GRID);
            grid.setAdapter(a);
            a.set(more.size() > 18 ? more.subList(0, 18) : more);
        }

        // সার্ভার থেকে স্ট্রিম এবং ডাউনলোড লিংক ফেচ করার কল
        fetchServerStreams();
        
        if (!movie) {
            showSeasons(d.optJSONArray("seasons"));
        }
    }

    private void fetchServerStreams() {
        io.execute(() -> {
            try {
                String apiUrl = "https://cinedor-server.vercel.app/api/stream?id=" + item.id + "&type=" + item.type;
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                if (json.optBoolean("success", false)) {
                    JSONArray streamsArr = json.optJSONArray("streams");
                    if (streamsArr != null) {
                        for (int i = 0; i < streamsArr.length(); i++) {
                            JSONObject obj = streamsArr.getJSONObject(i);
                            serverStreams.add(new MediaLink(
                                    obj.optString("server_name", "Server " + (i + 1)),
                                    obj.optString("url")
                            ));
                        }
                    }

                    JSONArray downloadsArr = json.optJSONArray("downloads");
                    if (downloadsArr != null) {
                        for (int i = 0; i < downloadsArr.length(); i++) {
                            JSONObject obj = downloadsArr.getJSONObject(i);
                            serverDownloads.add(new MediaLink(
                                    obj.optString("quality", "HD"),
                                    obj.optString("url")
                            ));
                        }
                    }
                }

                main.post(() -> {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    sourcesChecked = true;
                    if (!serverStreams.isEmpty()) {
                        status.setText("✓ Stream & Download ready — tap ▶ to play.");
                        status.setVisibility(View.VISIBLE);
                    }
                });

            } catch (Exception e) {
                main.post(() -> {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    sourcesChecked = true;
                });
            }
        });
    }

    private static String certification(JSONObject d, boolean movie) {
        try {
            JSONArray rs = d.getJSONObject(movie ? "release_dates" : "content_ratings").getJSONArray("results");
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                if (!"US".equals(r.optString("iso_3166_1"))) continue;
                if (!movie) return r.optString("rating");
                JSONArray dates = r.getJSONArray("release_dates");
                for (int k = 0; k < dates.length(); k++) {
                    String c = dates.getJSONObject(k).optString("certification");
                    if (!c.isEmpty()) return c;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void loadArchive(String id) {
        progress.setVisibility(View.GONE);
        sourcesChecked = true;
    }

    private static String metaLine(String year, String cert, String runtime, double rating) {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{year, cert, runtime, rating > 0 ? String.format(Locale.US, "★ %.1f", rating) : ""}) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append("   •   ");
            sb.append(s);
        }
        return sb.toString();
    }

    private void setOverview(String text) {
        overview.setText(text);
        showMore.setVisibility(text != null && text.length() > 190 ? View.VISIBLE : View.GONE);
    }

    private ImageView action(LinearLayout parent, int icon, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        ImageView iv = Ui.icon(this, icon, Ui.TEXT);
        b.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView t = Ui.text(this, label, 12.5f, Ui.SUB, false);
        t.setPadding(0, Ui.dp(this, 5), 0, 0);
        b.addView(t);
        parent.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        return iv;
    }

    private void showCast(JSONArray cast) {
        if (cast == null || cast.length() == 0) return;
        findViewById(R.id.castTitle).setVisibility(View.VISIBLE);
        LinearLayout box = findViewById(R.id.cast);
        for (int i = 0; i < cast.length() && i < 14; i++) {
            JSONObject p = cast.optJSONObject(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView photo = new ImageView(this);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.clip(photo, Ui.SURFACE, Ui.dp(this, 36));
            cell.addView(photo, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
            String pic = p.optString("profile_path", "");
            if (!pic.isEmpty() && !"null".equals(pic)) ImageLoader.load(photo, Tmdb.IMG + "w185" + pic);
            TextView n = Ui.text(this, p.optString("name"), 13, Ui.TEXT, true);
            n.setGravity(Gravity.CENTER);
            n.setMaxLines(2);
            n.setPadding(0, Ui.dp(this, 8), 0, 0);
            cell.addView(n);
            TextView ch = Ui.text(this, p.optString("character"), 12, Ui.SUB, false);
            ch.setGravity(Gravity.CENTER);
            ch.setMaxLines(2);
            cell.addView(ch);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 96), -2);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            box.addView(cell, lp);
        }
    }

    private void showSeasons(JSONArray seasons) {
        if (seasons == null) return;
        LinearLayout row = findViewById(R.id.seasons);
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject s = seasons.optJSONObject(i);
            int number = s.optInt("season_number");
            if (number <= 0) continue;
            TextView chip = Ui.chip(this, "Season " + number, false);
            chips.add(chip);
            chip.setOnClickListener(v -> {
                for (TextView c : chips) c.setBackground(Ui.round(c == chip ? Ui.ACCENT : Ui.SURFACE, Ui.dp(this, 20)));
                loadEpisodes(number);
            });
            row.addView(chip);
        }
        if (chips.isEmpty()) return;
        findViewById(R.id.seasonsBox).setVisibility(View.VISIBLE);
        chips.get(0).performClick();
    }

    private void loadEpisodes(int season) {
        LinearLayout box = findViewById(R.id.episodes);
        box.removeAllViews();
        box.setTag(season);
        io.execute(() -> {
            try {
                JSONArray eps = Tmdb.get("tv/" + item.id + "/season/" + season).optJSONArray("episodes");
                main.post(() -> {
                    if (isFinishing() || eps == null || !Integer.valueOf(season).equals(box.getTag())) return;
                    for (int i = 0; i < eps.length(); i++) {
                        JSONObject e = eps.optJSONObject(i);
                        View rowView = LayoutInflater.from(this).inflate(R.layout.item_quality, box, false);
                        ((TextView) rowView.findViewById(R.id.qLabel)).setText(
                                e.optInt("episode_number") + ".  " + e.optString("name"));
                        int rt = e.optInt("runtime");
                        ((TextView) rowView.findViewById(R.id.qInfo)).setText(rt > 0 ? rt + "m" : e.optString("air_date"));
                        rowView.setOnClickListener(v -> showUnavailable());
                        box.addView(rowView);
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    private void onPlay() {
        if (!sourcesChecked) { Toast.makeText(this, "Checking sources…", Toast.LENGTH_SHORT).show(); return; }
        if (serverStreams.isEmpty()) { showUnavailable(); return; }
        
        MediaLink best = serverStreams.get(0);
        play(best);
    }

    private void onDownload() {
        if (!sourcesChecked) { Toast.makeText(this, "Checking sources…", Toast.LENGTH_SHORT).show(); return; }
        if (serverDownloads.isEmpty()) { Toast.makeText(this, "No download links available", Toast.LENGTH_SHORT).show(); return; }
        
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.sheet_quality, null);
        ((TextView) root.findViewById(R.id.sheetTitle)).setText("Select quality to download");
        LinearLayout box = root.findViewById(R.id.sheetList);
        
        for (MediaLink dl : serverDownloads) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_quality, box, false);
            ((TextView) row.findViewById(R.id.qLabel)).setText(dl.name);
            ((TextView) row.findViewById(R.id.qInfo)).setText("Download");
            row.setOnClickListener(v -> { sheet.dismiss(); startDownload(dl); });
            box.addView(row);
        }
        sheet.setContentView(root);
        sheet.show();
    }

    private void showUnavailable() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this).inflate(R.layout.sheet_quality, null);
        ((TextView) root.findViewById(R.id.sheetTitle)).setText("Video isn't available");
        LinearLayout box = root.findViewById(R.id.sheetList);
        sheetRow(box, "▶  Watch trailer", trailerKey.isEmpty() ? "Not available" : "YouTube", v -> { sheet.dismiss(); openTrailer(); });
        sheet.setContentView(root);
        sheet.show();
    }

    private void sheetRow(LinearLayout box, String label, String info, View.OnClickListener click) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_quality, box, false);
        ((TextView) row.findViewById(R.id.qLabel)).setText(label);
        TextView i = row.findViewById(R.id.qInfo);
        i.setText(info);
        row.setOnClickListener(click);
        box.addView(row);
    }

    private void openTrailer() {
        if (!trailerKey.isEmpty()) {
            openLink("https://www.youtube.com/watch?v=" + trailerKey);
        } else {
            Toast.makeText(this, "Trailer not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show();
        }
    }

    private void share() {
        String link = "https://www.themoviedb.org/" + item.type + "/" + item.id;
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, item.title + "\n" + link);
        startActivity(Intent.createChooser(i, "Share"));
    }

    // PlayerActivity-এর সাথে মিল রেখে সঠিক key "video_url" দিয়ে ফিক্স করা হলো
    private void play(MediaLink s) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("video_url", s.url);
        i.putExtra("video_title", item.title);
        startActivity(i);
    }

    private void startDownload(MediaLink s) {
        try {
            Uri uri = Uri.parse(s.url);
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(uri);
            request.setTitle(item.title + " (" + s.name + ")");
            request.setDescription("Downloading via Cinedor...");
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, item.title.replaceAll("[^a-zA-Z0-9]", "_") + ".mp4");
            request.allowScanningByMediaScanner();

            android.app.DownloadManager manager = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                Toast.makeText(this, "Download started...", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
