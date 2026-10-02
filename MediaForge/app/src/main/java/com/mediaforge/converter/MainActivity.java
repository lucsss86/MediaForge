package com.mediaforge.converter;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK_MP4 = 1001;
    private static final int REQ_SAVE_MP3 = 1002;
    private static final int REQ_PICK_MP3 = 1003;
    private static final int REQ_SAVE_MP4 = 1004;
    private static final int REQ_SAVE_URL_MP3 = 1005;
    private static final int REQ_SAVE_URL_MP4 = 1006;

    private Uri pendingInputUri;
    private String pendingUrl;

    private EditText urlInput;
    private TextView statusText;
    private ProgressBar progress;
    private Button btnMp4ToMp3;
    private Button btnMp3ToMp4;
    private Button btnUrlToMp3;
    private Button btnUrlToMp4;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        statusText = findViewById(R.id.statusText);
        progress = findViewById(R.id.progress);
        btnMp4ToMp3 = findViewById(R.id.btnMp4ToMp3);
        btnMp3ToMp4 = findViewById(R.id.btnMp3ToMp4);
        btnUrlToMp3 = findViewById(R.id.btnUrlToMp3);
        btnUrlToMp4 = findViewById(R.id.btnUrlToMp4);

        btnMp4ToMp3.setOnClickListener(v -> pickFile("video/*", REQ_PICK_MP4));
        btnMp3ToMp4.setOnClickListener(v -> pickFile("audio/*", REQ_PICK_MP3));
        btnUrlToMp3.setOnClickListener(v -> beginUrlFlow("mp3"));
        btnUrlToMp4.setOnClickListener(v -> beginUrlFlow("mp4"));
    }

    private void pickFile(String mime, int requestCode) {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        startActivityForResult(intent, requestCode);
    }

    private void createOutputDocument(String mime, String suggestedName, int requestCode) {
        Intent intent = new Intent("android.intent.action.CREATE_DOCUMENT");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        startActivityForResult(intent, requestCode);
    }

    private void beginUrlFlow(String outputExt) {
        String value = urlInput.getText().toString().trim();
        if (!isValidHttpUrl(value)) {
            showToast("Paste a valid http:// or https:// media URL.");
            return;
        }
        pendingUrl = value;
        String name = suggestedUrlName(value, outputExt);
        if ("mp3".equals(outputExt)) {
            createOutputDocument("audio/mpeg", name, REQ_SAVE_URL_MP3);
        } else {
            createOutputDocument("video/mp4", name, REQ_SAVE_URL_MP4);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQ_PICK_MP4) {
            pendingInputUri = uri;
            createOutputDocument("audio/mpeg", replaceExtension(displayName(uri), "mp3"), REQ_SAVE_MP3);
        } else if (requestCode == REQ_PICK_MP3) {
            pendingInputUri = uri;
            createOutputDocument("video/mp4", replaceExtension(displayName(uri), "mp4"), REQ_SAVE_MP4);
        } else if (requestCode == REQ_SAVE_MP3) {
            convertPickedFile(pendingInputUri, uri, "mp4_to_mp3");
        } else if (requestCode == REQ_SAVE_MP4) {
            convertPickedFile(pendingInputUri, uri, "mp3_to_mp4");
        } else if (requestCode == REQ_SAVE_URL_MP3) {
            downloadAndProcessUrl(pendingUrl, uri, "mp3");
        } else if (requestCode == REQ_SAVE_URL_MP4) {
            downloadAndProcessUrl(pendingUrl, uri, "mp4");
        }
    }

    private void convertPickedFile(Uri inputUri, Uri outputUri, String mode) {
        if (inputUri == null) {
            showToast("Select the input file again.");
            return;
        }
        setBusy(true, "Preparing file…");
        ioExecutor.execute(() -> {
            File input = new File(getCacheDir(), "source_" + System.currentTimeMillis() + ("mp4_to_mp3".equals(mode) ? ".mp4" : ".mp3"));
            File output = new File(getCacheDir(), "result_" + System.currentTimeMillis() + ("mp4_to_mp3".equals(mode) ? ".mp3" : ".mp4"));
            try {
                copyUriToFile(inputUri, input);
                runOnUiThread(() -> updateStatus("Converting on-device…"));
                String command;
                if ("mp4_to_mp3".equals(mode)) {
                    command = "-y -i " + q(input) + " -vn -c:a libmp3lame -q:a 2 " + q(output);
                } else {
                    command = audioToVideoCommand(input, output);
                }
                executeFfmpeg(command, output, outputUri, input);
            } catch (Exception e) {
                safeDelete(input);
                safeDelete(output);
                fail("Could not read the selected file: " + friendlyMessage(e));
            }
        });
    }

    private void downloadAndProcessUrl(String address, Uri outputUri, String outputExt) {
        if (!isValidHttpUrl(address)) {
            showToast("The URL is no longer valid. Paste it again.");
            return;
        }
        setBusy(true, "Connecting to media URL…");
        ioExecutor.execute(() -> {
            File downloaded = new File(getCacheDir(), "download_" + System.currentTimeMillis() + ".bin");
            File converted = new File(getCacheDir(), "url_result_" + System.currentTimeMillis() + "." + outputExt);
            HttpURLConnection connection = null;
            try {
                URL url = new URL(address);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "MediaForge/1.0 Android");
                connection.setRequestProperty("Accept", "audio/*,video/*,application/octet-stream;q=0.9,*/*;q=0.1");
                connection.connect();

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("Server returned HTTP " + code);
                }

                String contentType = connection.getContentType();
                long total = connection.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(downloaded))) {
                    byte[] buffer = new byte[64 * 1024];
                    long received = 0;
                    int read;
                    int lastPercent = -1;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        received += read;
                        if (total > 0) {
                            int percent = (int) Math.min(100, (received * 100L) / total);
                            if (percent >= lastPercent + 5) {
                                lastPercent = percent;
                                final int p = percent;
                                runOnUiThread(() -> updateStatus("Downloading… " + p + "%"));
                            }
                        }
                    }
                }

                if (downloaded.length() == 0) {
                    throw new IllegalStateException("The server returned an empty file.");
                }
                if (looksLikeHtml(downloaded) || isHtmlContentType(contentType)) {
                    throw new IllegalArgumentException("This URL points to a webpage, not a direct media file.");
                }

                boolean directMp3 = "mp3".equals(outputExt) && sourceLooksMp3(address, contentType);
                boolean directMp4 = "mp4".equals(outputExt) && sourceLooksMp4(address, contentType);
                if (directMp3 || directMp4) {
                    runOnUiThread(() -> updateStatus("Saving downloaded media…"));
                    copyFileToUri(downloaded, outputUri);
                    safeDelete(downloaded);
                    safeDelete(converted);
                    success("Saved successfully.");
                    return;
                }

                runOnUiThread(() -> updateStatus("Download complete. Converting…"));
                String command;
                if ("mp3".equals(outputExt)) {
                    command = "-y -i " + q(downloaded) + " -vn -c:a libmp3lame -q:a 2 " + q(converted);
                } else if (sourceLooksVideo(address, contentType)) {
                    command = "-y -i " + q(downloaded)
                            + " -map 0:v:0? -map 0:a:0? -c:v mpeg4 -q:v 4 -pix_fmt yuv420p"
                            + " -c:a aac -b:a 192k -movflags +faststart " + q(converted);
                } else {
                    command = audioToVideoCommand(downloaded, converted);
                }
                executeFfmpeg(command, converted, outputUri, downloaded);
            } catch (Exception e) {
                safeDelete(downloaded);
                safeDelete(converted);
                fail("URL download failed: " + friendlyMessage(e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private String audioToVideoCommand(File input, File output) {
        return "-y -f lavfi -i color=c=0x0B1020:s=1280x720:r=30 -i " + q(input)
                + " -map 0:v:0 -map 1:a:0 -shortest -c:v mpeg4 -q:v 4 -pix_fmt yuv420p"
                + " -c:a aac -b:a 192k -movflags +faststart " + q(output);
    }

    private void executeFfmpeg(String command, File output, Uri destination, File cleanupInput) {
        FFmpegKit.executeAsync(command, session -> {
            ReturnCode rc = session.getReturnCode();
            if (ReturnCode.isSuccess(rc) && output.exists() && output.length() > 0) {
                ioExecutor.execute(() -> {
                    try {
                        runOnUiThread(() -> updateStatus("Writing output file…"));
                        copyFileToUri(output, destination);
                        safeDelete(cleanupInput);
                        safeDelete(output);
                        success("Conversion completed successfully.");
                    } catch (Exception e) {
                        safeDelete(cleanupInput);
                        safeDelete(output);
                        fail("Conversion finished, but the output could not be saved: " + friendlyMessage(e));
                    }
                });
            } else {
                String log = session.getOutput();
                safeDelete(cleanupInput);
                safeDelete(output);
                fail("Conversion failed." + conciseFfmpegHint(log));
            }
        });
    }

    private void copyUriToFile(Uri uri, File destination) throws Exception {
        ContentResolver resolver = getContentResolver();
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            if (in == null) throw new IllegalStateException("Could not open input stream.");
            copy(in, out);
        }
    }

    private void copyFileToUri(File source, Uri destination) throws Exception {
        ContentResolver resolver = getContentResolver();
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = resolver.openOutputStream(destination, "w")) {
            if (out == null) throw new IllegalStateException("Could not open output stream.");
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private String displayName(Uri uri) {
        String result = "media";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) result = cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return result == null || result.trim().isEmpty() ? "media" : result;
    }

    private static String replaceExtension(String name, String ext) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = sanitizeFileName(base);
        return base + "." + ext;
    }

    private static String suggestedUrlName(String address, String ext) {
        try {
            String path = new URL(address).getPath();
            String last = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            if (!last.isEmpty()) return replaceExtension(last, ext);
        } catch (Exception ignored) {
        }
        return "mediaforge_" + System.currentTimeMillis() + "." + ext;
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "media" : cleaned;
    }

    private static boolean isValidHttpUrl(String value) {
        try {
            URL url = new URL(value);
            String protocol = url.getProtocol();
            return ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol)) && url.getHost() != null && !url.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHtmlContentType(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase(Locale.US);
        return t.contains("text/html") || t.contains("application/xhtml");
    }

    private static boolean looksLikeHtml(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[512];
            int n = in.read(head);
            if (n <= 0) return false;
            String s = new String(head, 0, n, "UTF-8").trim().toLowerCase(Locale.US);
            return s.startsWith("<!doctype html") || s.startsWith("<html") || s.contains("<head") || s.contains("<body");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sourceLooksMp3(String address, String contentType) {
        String u = address.toLowerCase(Locale.US);
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return t.startsWith("audio/mpeg") || t.contains("audio/mp3") || u.matches(".*\\.mp3(?:\\?.*)?$");
    }

    private static boolean sourceLooksMp4(String address, String contentType) {
        String u = address.toLowerCase(Locale.US);
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return t.startsWith("video/mp4") || u.matches(".*\\.(mp4|m4v)(?:\\?.*)?$");
    }

    private static boolean sourceLooksVideo(String address, String contentType) {
        String u = address.toLowerCase(Locale.US);
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return t.startsWith("video/") || u.matches(".*\\.(mp4|m4v|mov|mkv|webm|avi|3gp|mpeg|mpg)(?:\\?.*)?$");
    }

    private static String q(File file) {
        return "\"" + file.getAbsolutePath().replace("\"", "\\\"") + "\"";
    }

    private static void safeDelete(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String conciseFfmpegHint(String log) {
        if (log == null || log.trim().isEmpty()) return " The file may not contain a compatible audio/video stream.";
        String lower = log.toLowerCase(Locale.US);
        if (lower.contains("stream map") || lower.contains("matches no streams")) {
            return " The source does not contain the required audio/video stream.";
        }
        if (lower.contains("invalid data")) {
            return " The source does not appear to be a valid media file.";
        }
        return " Check that the source file is a supported media format.";
    }

    private static String friendlyMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private void setBusy(boolean busy, String message) {
        runOnUiThread(() -> {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            statusText.setTextColor(getResources().getColor(R.color.text_secondary));
            btnMp4ToMp3.setEnabled(!busy);
            btnMp3ToMp4.setEnabled(!busy);
            btnUrlToMp3.setEnabled(!busy);
            btnUrlToMp4.setEnabled(!busy);
            urlInput.setEnabled(!busy);
            statusText.setText(message);
        });
    }

    private void updateStatus(String message) {
        statusText.setText(message);
    }

    private void success(String message) {
        runOnUiThread(() -> {
            setBusy(false, message);
            statusText.setTextColor(getResources().getColor(R.color.success));
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            setBusy(false, message);
            statusText.setTextColor(getResources().getColor(R.color.error));
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }
}
