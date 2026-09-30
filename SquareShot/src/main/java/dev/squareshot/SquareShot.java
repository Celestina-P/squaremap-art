package dev.squareshot;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SquareShot: ghép các tile của squaremap thành 1 ảnh và gửi / sửa 1 tin nhắn
 * cố định trong kênh Discord thông qua webhook. Chỉ dùng thư viện có sẵn của Java.
 */
public final class SquareShot extends JavaPlugin {

    private static final Pattern TILE = Pattern.compile("^(-?\\d+)_(-?\\d+)\\.png$");
    private static final int MAX_UPLOAD_BYTES = 8_000_000;

    private record Settings(String webhook, int intervalMinutes, String world, int maxSize,
                            File tilesDir, String title, String mapUrl) {
    }

    private record Tile(int x, int z, File file) {
    }

    private record Layer(File dir, List<Tile> tiles, int minX, int maxX, int minZ, int maxZ) {
        int cols() {
            return maxX - minX + 1;
        }

        int rows() {
            return maxZ - minZ + 1;
        }

        int longest() {
            return Math.max(cols(), rows());
        }
    }

    private volatile Settings settings;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private File stateFile;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public void onEnable() {
        System.setProperty("java.awt.headless", "true");
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.properties");
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SquareShot-Worker");
            t.setDaemon(true);
            return t;
        });
        applyConfig();
    }

    @Override
    public void onDisable() {
        if (future != null) {
            future.cancel(false);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void applyConfig() {
        reloadConfig();
        var c = getConfig();
        String webhook = c.getString("webhook-url", "").trim();
        int interval = Math.max(1, c.getInt("interval-minutes", 5));
        String world = c.getString("world", "").trim();
        int maxSize = Math.max(256, Math.min(4096, c.getInt("max-image-size", 2048)));
        String tilesPath = c.getString("squaremap-tiles-dir", "").trim();
        File tilesDir = tilesPath.isEmpty()
                ? new File(getDataFolder().getParentFile(), "squaremap/web/tiles")
                : new File(tilesPath);
        String title = c.getString("title", "Bản đồ server");
        String mapUrl = c.getString("map-url", "").trim();
        settings = new Settings(webhook, interval, world, maxSize, tilesDir, title, mapUrl);

        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (webhook.isEmpty()) {
            getLogger().warning("Chưa điền webhook-url trong plugins/SquareShot/config.yml, plugin chưa hoạt động.");
            return;
        }
        if (!webhook.startsWith("https://discord.com/api/webhooks/")
                && !webhook.startsWith("https://discordapp.com/api/webhooks/")) {
            getLogger().warning("webhook-url có vẻ không phải URL webhook của Discord.");
        }
        future = executor.scheduleWithFixedDelay(this::safeRun, 20, interval * 60L, TimeUnit.SECONDS);
        getLogger().info("Sẽ cập nhật ảnh bản đồ mỗi " + interval + " phút (lần đầu sau 20 giây).");
    }

    private void safeRun() {
        try {
            runOnce();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Cập nhật bản đồ thất bại: " + t.getMessage());
        }
    }

    void runOnce() throws Exception {
        Settings s = settings;
        if (s == null || s.webhook().isEmpty()) {
            throw new IOException("Chưa điền webhook-url trong config.yml");
        }
        int size = s.maxSize();
        byte[] png = render(s, size);
        while (png.length > MAX_UPLOAD_BYTES && size > 512) {
            size /= 2;
            png = render(s, size);
        }
        postOrEdit(s, png);
    }

    // ------------------------------------------------------------------ ghép ảnh

    byte[] render(Settings s, int maxSize) throws IOException {
        File[] worlds = s.tilesDir().listFiles(File::isDirectory);
        if (worlds == null || worlds.length == 0) {
            throw new IOException("Không thấy thư mục tile của squaremap: " + s.tilesDir().getPath());
        }
        File worldDir = pickWorld(worlds, s.world());
        File[] zoomDirs = worldDir.listFiles(File::isDirectory);
        List<Layer> layers = new ArrayList<>();
        if (zoomDirs != null) {
            for (File z : zoomDirs) {
                Layer l = scan(z);
                if (l != null) {
                    layers.add(l);
                }
            }
        }
        if (layers.isEmpty()) {
            throw new IOException("Không thấy tile dạng x_z.png trong " + worldDir.getPath()
                    + ". Hãy chạy squaremap fullrender cho thế giới này trước.");
        }

        int tileSize = detectTileSize(layers.get(0));

        // Chọn lớp zoom chi tiết nhất mà vẫn vừa khung maxSize; nếu không có thì lấy lớp nhỏ nhất.
        Layer best = null;
        for (Layer l : layers) {
            if ((long) l.longest() * tileSize <= maxSize && (best == null || l.longest() > best.longest())) {
                best = l;
            }
        }
        if (best == null) {
            best = layers.get(0);
            for (Layer l : layers) {
                if (l.longest() < best.longest()) {
                    best = l;
                }
            }
        }

        double fullW = (double) best.cols() * tileSize;
        double fullH = (double) best.rows() * tileSize;
        double scale = Math.min(1.0, (double) maxSize / Math.max(fullW, fullH));
        int outW = Math.max(1, (int) Math.round(fullW * scale));
        int outH = Math.max(1, (int) Math.round(fullH * scale));

        BufferedImage canvas = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new Color(0x1E1F22));
            g.fillRect(0, 0, outW, outH);
            for (Tile t : best.tiles()) {
                BufferedImage img;
                try {
                    img = ImageIO.read(t.file());
                } catch (Exception e) {
                    continue; // tile đang được squaremap ghi dở, bỏ qua lần này
                }
                if (img == null) {
                    continue;
                }
                int cx = t.x() - best.minX();
                int cz = t.z() - best.minZ();
                int x0 = (int) Math.round(cx * tileSize * scale);
                int y0 = (int) Math.round(cz * tileSize * scale);
                int x1 = (int) Math.round((cx + 1) * tileSize * scale);
                int y1 = (int) Math.round((cz + 1) * tileSize * scale);
                g.drawImage(img, x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), null);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", out)) {
            throw new IOException("Không mã hóa được ảnh PNG");
        }
        return out.toByteArray();
    }

    private static File pickWorld(File[] dirs, String want) {
        Arrays.sort(dirs);
        String w = want.toLowerCase(Locale.ROOT);
        if (!w.isEmpty()) {
            for (File d : dirs) {
                if (d.getName().toLowerCase(Locale.ROOT).contains(w)) {
                    return d;
                }
            }
        }
        for (File d : dirs) {
            if (d.getName().toLowerCase(Locale.ROOT).contains("overworld")) {
                return d;
            }
        }
        for (File d : dirs) {
            if (d.getName().equalsIgnoreCase("world")) {
                return d;
            }
        }
        return dirs[0];
    }

    private static Layer scan(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        List<Tile> tiles = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (File f : files) {
            Matcher m = TILE.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            int x;
            int z;
            try {
                x = Integer.parseInt(m.group(1));
                z = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            tiles.add(new Tile(x, z, f));
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        if (tiles.isEmpty()) {
            return null;
        }
        return new Layer(dir, tiles, minX, maxX, minZ, maxZ);
    }

    private static int detectTileSize(Layer layer) {
        for (Tile t : layer.tiles()) {
            try {
                BufferedImage img = ImageIO.read(t.file());
                if (img != null && img.getWidth() > 0) {
                    return img.getWidth();
                }
            } catch (Exception ignored) {
                // thử tile khác
            }
        }
        return 512;
    }

    // ------------------------------------------------------------------ Discord

    void postOrEdit(Settings s, byte[] png) throws Exception {
        String base = stripQuery(s.webhook());
        String json = buildPayload(s);

        String id = loadMessageId(base);
        if (id != null) {
            HttpResponse<String> r = send("PATCH", base + "/messages/" + id, json, png);
            if (r.statusCode() / 100 == 2) {
                return;
            }
            if (r.statusCode() != 404) {
                throw new IOException("Discord trả về " + r.statusCode() + ": " + shorten(r.body()));
            }
            // 404: tin nhắn cũ đã bị xóa, gửi tin mới bên dưới
        }

        HttpResponse<String> r = send("POST", base + "?wait=true", json, png);
        if (r.statusCode() / 100 != 2) {
            throw new IOException("Discord trả về " + r.statusCode() + ": " + shorten(r.body()));
        }
        String newId = topLevelId(r.body());
        if (newId != null) {
            saveMessageId(base, newId);
        }
    }

    private static String buildPayload(Settings s) {
        long now = System.currentTimeMillis() / 1000L;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"content\":").append(jsonStr("Cập nhật lần cuối: <t:" + now + ":R>"));
        sb.append(",\"embeds\":[{\"title\":").append(jsonStr(s.title()));
        if (s.mapUrl().startsWith("http://") || s.mapUrl().startsWith("https://")) {
            sb.append(",\"url\":").append(jsonStr(s.mapUrl()));
        }
        sb.append(",\"color\":5763719,\"image\":{\"url\":\"attachment://map.png\"}}]");
        sb.append(",\"attachments\":[{\"id\":0,\"filename\":\"map.png\"}]}");
        return sb.toString();
    }

    private HttpResponse<String> send(String method, String url, String json, byte[] png) throws Exception {
        String boundary = "SquareShot" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, json, png);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", "SquareShot/1.0")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] multipart(String boundary, String json, byte[] png) throws IOException {
        String nl = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 1024);
        out.write(("--" + boundary + nl
                + "Content-Disposition: form-data; name=\"payload_json\"" + nl
                + "Content-Type: application/json" + nl + nl).getBytes(StandardCharsets.UTF_8));
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.write(nl.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + nl
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"map.png\"" + nl
                + "Content-Type: image/png" + nl + nl).getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write(nl.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--" + nl).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ tiện ích

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        String u = q < 0 ? url : url.substring(0, q);
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Lấy giá trị của khóa "id" ở cấp ngoài cùng của một object JSON. */
    static String topLevelId(String json) {
        int depth = 0;
        int i = 0;
        int n = json.length();
        while (i < n) {
            char ch = json.charAt(i);
            if (ch == '{' || ch == '[') {
                depth++;
                i++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                i++;
            } else if (ch == '"') {
                int end = endOfString(json, i);
                String str = json.substring(i + 1, end);
                i = end + 1;
                if (depth == 1 && str.equals("id")) {
                    int k = i;
                    while (k < n && Character.isWhitespace(json.charAt(k))) {
                        k++;
                    }
                    if (k < n && json.charAt(k) == ':') {
                        k++;
                        while (k < n && Character.isWhitespace(json.charAt(k))) {
                            k++;
                        }
                        if (k < n && json.charAt(k) == '"') {
                            int e2 = endOfString(json, k);
                            return json.substring(k + 1, e2);
                        }
                    }
                }
            } else {
                i++;
            }
        }
        return null;
    }

    private static int endOfString(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return s.length() - 1;
    }

    private static String hash(String s) {
        return Integer.toHexString(s.hashCode());
    }

    private String loadMessageId(String base) {
        if (!stateFile.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(stateFile.toPath())) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        if (!hash(base).equals(p.getProperty("webhook"))) {
            return null;
        }
        return p.getProperty("message");
    }

    private void saveMessageId(String base, String id) {
        Properties p = new Properties();
        p.setProperty("webhook", hash(base));
        p.setProperty("message", id);
        getDataFolder().mkdirs();
        try (OutputStream out = Files.newOutputStream(stateFile.toPath())) {
            p.store(out, "SquareShot state");
        } catch (IOException e) {
            getLogger().warning("Không lưu được state.properties: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ lệnh

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("squareshot.admin")) {
            sender.sendMessage("Bạn không có quyền dùng lệnh này.");
            return true;
        }
        String sub = args.length == 0 ? "now" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                applyConfig();
                sender.sendMessage("Đã tải lại config SquareShot.");
            }
            case "now" -> executor.submit(() -> {
                try {
                    runOnce();
                    sender.sendMessage("Đã gửi/cập nhật ảnh bản đồ lên Discord.");
                } catch (Throwable t) {
                    sender.sendMessage("Lỗi: " + t.getMessage());
                }
            });
            default -> sender.sendMessage("Dùng: /" + label + " <now|reload>");
        }
        return true;
    }
}
