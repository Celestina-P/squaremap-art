package dev.squareshot;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SquareShot: ghép các tile của squaremap thành 1 ảnh (kèm đầu, tên và thanh máu người chơi)
 * rồi gửi / sửa 1 tin nhắn cố định trong kênh Discord thông qua webhook.
 */
public final class SquareShot extends JavaPlugin {

    private static final Pattern TILE = Pattern.compile("^(-?\\d+)_(-?\\d+)\\.png$");
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final int MAX_UPLOAD_BYTES = 8_000_000;
    private static final int TILE_BLOCKS = 512; // squaremap: mỗi tile = 512x512 pixel

    record Settings(String webhook, int intervalMinutes, String world, int maxSize, File tilesDir,
                    String title, String mapUrl, String mode, double centerX, double centerZ,
                    double radius, double playersMinRadius, double playersMargin, double pixelsPerBlock,
                    boolean showPlayers, boolean showHealth, boolean showHeads,
                    double aspect, boolean useEmbed) {
    }

    record PlayerInfo(String name, List<String> worldIds, double x, double z, float yaw, double health) {
    }

    private record Tile(int x, int z, File file) {
    }

    /** step = số block trên mỗi pixel của lớp zoom này (1 = chi tiết nhất). */
    private record Layer(int zoom, int step, List<Tile> tiles, int minX, int maxX, int minZ, int maxZ) {
    }

    private record CachedHead(BufferedImage image, long at) {
    }

    private volatile Settings settings;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private File stateFile;
    private final ConcurrentHashMap<String, CachedHead> heads = new ConcurrentHashMap<>();
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
        int maxSize = Math.max(256, Math.min(6144, c.getInt("max-image-size", 2048)));
        String tilesPath = c.getString("squaremap-tiles-dir", "").trim();
        File tilesDir = tilesPath.isEmpty()
                ? new File(getDataFolder().getParentFile(), "squaremap/web/tiles")
                : new File(tilesPath);
        String title = c.getString("title", "Bản đồ server");
        String mapUrl = c.getString("map-url", "").trim();
        String mode = c.getString("mode", "center").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("full") && !mode.equals("center") && !mode.equals("players")) {
            getLogger().warning("mode '" + mode + "' không hợp lệ (full | center | players), dùng center.");
            mode = "center";
        }
        double radius = Math.max(16, c.getDouble("radius-blocks", 400));
        double minRadius = Math.max(16, c.getDouble("players-min-radius", 150));
        double margin = Math.max(0, c.getDouble("players-margin", 100));
        double ppb = Math.max(0.05, Math.min(8, c.getDouble("pixels-per-block", 2)));
        settings = new Settings(webhook, interval, world, maxSize, tilesDir, title, mapUrl, mode,
                c.getDouble("center-x", 0), c.getDouble("center-z", 0), radius, minRadius, margin, ppb,
                c.getBoolean("show-players", true), c.getBoolean("show-health", true),
                c.getBoolean("show-heads", true),
                parseAspect(c.getString("aspect-ratio", "16:9")), c.getBoolean("use-embed", false));

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
        getLogger().info("Sẽ cập nhật ảnh bản đồ mỗi " + interval + " phút (lần đầu sau 20 giây), chế độ: " + mode + ".");
    }

    /** "16:9" -> 1.777..., "1:1" -> 1, "free" / "none" -> 0 (không ép tỉ lệ). */
    static double parseAspect(String v) {
        String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT).replace('x', ':');
        if (t.isEmpty() || t.equals("free") || t.equals("none") || t.equals("auto")) {
            return 0;
        }
        if (t.equals("square")) {
            return 1;
        }
        String[] parts = t.split(":");
        if (parts.length == 2) {
            try {
                double a = Double.parseDouble(parts[0].trim());
                double b = Double.parseDouble(parts[1].trim());
                if (a > 0 && b > 0 && a / b >= 0.2 && a / b <= 5) {
                    return a / b;
                }
            } catch (NumberFormatException ignored) {
                // rơi xuống trả về 0
            }
        }
        return 0;
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
        List<PlayerInfo> players = s.showPlayers() ? snapshotPlayers() : List.of();
        int size = s.maxSize();
        byte[] png = render(s, size, players);
        while (png.length > MAX_UPLOAD_BYTES && size > 512) {
            size /= 2;
            png = render(s, size, players);
        }
        postOrEdit(s, png);
    }

    // ------------------------------------------------------------------ người chơi

    private List<PlayerInfo> snapshotPlayers() {
        try {
            return getServer().getScheduler().callSyncMethod(this, () -> {
                List<PlayerInfo> list = new ArrayList<>();
                for (Player p : getServer().getOnlinePlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }
                    Location loc = p.getLocation();
                    World w = loc.getWorld();
                    if (w == null) {
                        continue;
                    }
                    list.add(new PlayerInfo(p.getName(), worldIds(w), loc.getX(), loc.getZ(),
                            loc.getYaw(), p.getHealth()));
                }
                return list;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return List.of(); // server đang tạm dừng (không có ai online) hoặc bị treo
        }
    }

    private static List<String> worldIds(World w) {
        List<String> ids = new ArrayList<>();
        try {
            NamespacedKey k = w.getKey();
            ids.add((k.getNamespace() + "_" + k.getKey()).toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            // bỏ qua, dùng các cách khớp khác bên dưới
        }
        World.Environment env = w.getEnvironment();
        if (env == World.Environment.NETHER) {
            ids.add("minecraft_the_nether");
        } else if (env == World.Environment.THE_END) {
            ids.add("minecraft_the_end");
        } else if (env == World.Environment.NORMAL) {
            ids.add("minecraft_overworld");
        }
        ids.add(w.getName().toLowerCase(Locale.ROOT));
        return ids;
    }

    private BufferedImage headOf(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            return null;
        }
        CachedHead cached = heads.get(name);
        long now = System.currentTimeMillis();
        if (cached != null && (cached.image() != null || now - cached.at() < 600_000L)) {
            return cached.image();
        }
        BufferedImage img = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://mc-heads.net/avatar/" + name + "/16"))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "SquareShot/1.2")
                    .GET().build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 == 2) {
                img = ImageIO.read(new ByteArrayInputStream(r.body()));
            }
        } catch (Exception ignored) {
            // không tải được đầu, sẽ vẽ ô màu thay thế
        }
        heads.put(name, new CachedHead(img, now));
        return img;
    }

    // ------------------------------------------------------------------ ghép ảnh

    byte[] render(Settings s, int maxSize, List<PlayerInfo> players) throws IOException {
        File[] worlds = s.tilesDir().listFiles(File::isDirectory);
        if (worlds == null || worlds.length == 0) {
            throw new IOException("Không thấy thư mục tile của squaremap: " + s.tilesDir().getPath());
        }
        File worldDir = pickWorld(worlds, s.world());
        String worldName = worldDir.getName().toLowerCase(Locale.ROOT);

        // các lớp zoom: thư mục số lớn nhất = chi tiết nhất (1 pixel = 1 block)
        File[] zoomDirs = worldDir.listFiles(File::isDirectory);
        int maxZoom = -1;
        List<File> numeric = new ArrayList<>();
        if (zoomDirs != null) {
            for (File d : zoomDirs) {
                try {
                    maxZoom = Math.max(maxZoom, Integer.parseInt(d.getName()));
                    numeric.add(d);
                } catch (NumberFormatException ignored) {
                    // không phải thư mục zoom
                }
            }
        }
        List<Layer> layers = new ArrayList<>();
        for (File d : numeric) {
            int zoom = Integer.parseInt(d.getName());
            int shift = Math.min(20, Math.max(0, maxZoom - zoom));
            Layer l = scan(d, zoom, 1 << shift);
            if (l != null) {
                layers.add(l);
            }
        }
        if (layers.isEmpty()) {
            throw new IOException("Không thấy tile dạng x_z.png trong " + worldDir.getPath()
                    + ". Hãy chạy squaremap fullrender cho thế giới này trước.");
        }

        List<PlayerInfo> here = new ArrayList<>();
        for (PlayerInfo p : players) {
            if (p.worldIds().contains(worldName)) {
                here.add(p);
            }
        }

        // vùng cần vẽ, tính theo tọa độ block: (cx, cz) là tâm, w x h là kích thước
        double cx;
        double cz;
        double w;
        double h;
        double aspect = s.aspect();
        if (s.mode().equals("full")) {
            Layer coarse = layers.get(0);
            for (Layer l : layers) {
                if (l.step() > coarse.step()) {
                    coarse = l;
                }
            }
            double span = (double) TILE_BLOCKS * coarse.step();
            double x0 = coarse.minX() * span;
            double x1 = (coarse.maxX() + 1) * span;
            double z0 = coarse.minZ() * span;
            double z1 = (coarse.maxZ() + 1) * span;
            cx = (x0 + x1) / 2;
            cz = (z0 + z1) / 2;
            w = x1 - x0;
            h = z1 - z0;
            if (aspect > 0) { // nới rộng cho đủ tỉ lệ, không cắt bớt bản đồ
                if (w / h < aspect) {
                    w = h * aspect;
                } else {
                    h = w / aspect;
                }
            }
        } else {
            cx = s.centerX();
            cz = s.centerZ();
            double halfW = s.radius(); // radius-blocks là nửa chiều RỘNG
            if (s.mode().equals("players") && !here.isEmpty()) {
                double minX = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE;
                double maxZ = -Double.MAX_VALUE;
                for (PlayerInfo p : here) {
                    minX = Math.min(minX, p.x());
                    maxX = Math.max(maxX, p.x());
                    minZ = Math.min(minZ, p.z());
                    maxZ = Math.max(maxZ, p.z());
                }
                cx = (minX + maxX) / 2;
                cz = (minZ + maxZ) / 2;
                double needW = (maxX - minX) / 2 + s.playersMargin();
                double needH = (maxZ - minZ) / 2 + s.playersMargin();
                halfW = Math.max(s.playersMinRadius(), Math.max(needW, aspect > 0 ? needH * aspect : needH));
            }
            w = halfW * 2;
            h = aspect > 0 ? w / aspect : w;
        }
        double bx0 = cx - w / 2;
        double bx1 = cx + w / 2;
        double bz0 = cz - h / 2;
        double bz1 = cz + h / 2;
        double ppb = Math.min(s.mode().equals("full") ? 4.0 : s.pixelsPerBlock(), (double) maxSize / Math.max(w, h));
        int outW = Math.max(1, (int) Math.round(w * ppb));
        int outH = Math.max(1, (int) Math.round(h * ppb));
        double sx = outW / w; // pixel trên mỗi block
        double sz = outH / h;

        // chọn lớp zoom: thô nhất mà vẫn đủ nét cho độ phóng đại yêu cầu
        double blocksPerPixel = 1.0 / Math.max(sx, sz);
        Layer chosen = null;
        for (Layer l : layers) {
            if (l.step() <= blocksPerPixel + 1e-9 && (chosen == null || l.step() > chosen.step())) {
                chosen = l;
            }
        }
        if (chosen == null) {
            chosen = layers.get(0);
            for (Layer l : layers) {
                if (l.step() < chosen.step()) {
                    chosen = l;
                }
            }
        }

        BufferedImage canvas = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(new Color(0x1E1F22));
            g.fillRect(0, 0, outW, outH);
            boolean upscale = chosen.step() * Math.max(sx, sz) > 1.0 + 1e-9;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, upscale
                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double span = (double) TILE_BLOCKS * chosen.step();
            int drawn = 0;
            for (Tile t : chosen.tiles()) {
                double tx0 = t.x() * span;
                double tz0 = t.z() * span;
                double tx1 = tx0 + span;
                double tz1 = tz0 + span;
                if (tx1 <= bx0 || tx0 >= bx1 || tz1 <= bz0 || tz0 >= bz1) {
                    continue;
                }
                BufferedImage img;
                try {
                    img = ImageIO.read(t.file());
                } catch (Exception e) {
                    continue; // tile đang được squaremap ghi dở, bỏ qua lần này
                }
                if (img == null) {
                    continue;
                }
                int dx0 = (int) Math.round((tx0 - bx0) * sx);
                int dz0 = (int) Math.round((tz0 - bz0) * sz);
                int dx1 = (int) Math.round((tx1 - bx0) * sx);
                int dz1 = (int) Math.round((tz1 - bz0) * sz);
                g.drawImage(img, dx0, dz0, Math.max(1, dx1 - dx0), Math.max(1, dz1 - dz0), null);
                drawn++;
            }
            if (drawn == 0) {
                throw new IOException(String.format(Locale.ROOT,
                        "Vùng quanh (%.0f, %.0f) chưa có tile nào. Đổi center-x/center-z, hoặc render thêm bản đồ.",
                        (bx0 + bx1) / 2, (bz0 + bz1) / 2));
            }

            if (s.showPlayers()) {
                for (PlayerInfo p : here) {
                    int px = (int) Math.round((p.x() - bx0) * sx);
                    int pz = (int) Math.round((p.z() - bz0) * sz);
                    if (px < -30 || pz < -30 || px > outW + 30 || pz > outH + 30) {
                        continue;
                    }
                    BufferedImage head = s.showHeads() ? headOf(p.name()) : null;
                    Overlay.drawPlayer(g, outW, outH, p, px, pz, head, s.showHealth());
                }
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

    private static Layer scan(File dir, int zoom, int step) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        List<Tile> tiles = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
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
        return new Layer(zoom, step, tiles, minX, maxX, minZ, maxZ);
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
        if (s.useEmbed()) {
            sb.append("{\"content\":").append(jsonStr("Cập nhật lần cuối: <t:" + now + ":R>"));
            sb.append(",\"embeds\":[{\"title\":").append(jsonStr(s.title()));
            if (s.mapUrl().startsWith("http://") || s.mapUrl().startsWith("https://")) {
                sb.append(",\"url\":").append(jsonStr(s.mapUrl()));
            }
            sb.append(",\"color\":5763719,\"image\":{\"url\":\"attachment://map.png\"}}]");
        } else {
            // gửi ảnh dạng file đính kèm: Discord hiển thị to hơn ảnh trong embed
            StringBuilder text = new StringBuilder("**").append(s.title()).append("**");
            if (s.mapUrl().startsWith("http://") || s.mapUrl().startsWith("https://")) {
                text.append(" • <").append(s.mapUrl()).append(">");
            }
            text.append("\nCập nhật lần cuối: <t:").append(now).append(":R>");
            sb.append("{\"content\":").append(jsonStr(text.toString()));
            sb.append(",\"embeds\":[]");
        }
        sb.append(",\"attachments\":[{\"id\":0,\"filename\":\"map.png\"}]}");
        return sb.toString();
    }

    private HttpResponse<String> send(String method, String url, String json, byte[] png) throws Exception {
        String boundary = "SquareShot" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, json, png);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", "SquareShot/1.2")
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
