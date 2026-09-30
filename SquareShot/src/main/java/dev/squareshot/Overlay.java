package dev.squareshot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Vẽ đầu, tên, thanh máu và mũi tên hướng nhìn của người chơi lên ảnh bản đồ.
 * Không dùng font của AWT (nhiều máy chủ Linux không có fontconfig) mà tự vẽ chữ bằng bitmap.
 */
final class Overlay {

    private Overlay() {
    }

    /** Font bitmap 6x11 cho ASCII 32..126, mỗi hàng là 1 số 6 bit viết dưới dạng 2 ký tự hex. */
    private static final String[] GLYPH_HEX = {
            "0000000000000000000000", "0000001818181800180000", "0000001414140000000000", "000014143E14143E141400", "00081E323C1E06363C0800", "0000382A3C081E2A0E0000",
            "0000001C30183E2C3E0000", "00000C0810000000000000", "0000040818181818080400", "000010080C0C0C0C081000", "0000083C18240000000000", "00000008083E0808000000",
            "00000000000000000C0810", "00000000003E0000000000", "0000000000000000180000", "0000020204040808101000", "00001C36363636361C0000", "00000C3C0C0C0C0C3F0000",
            "00001C36060C18363E0000", "00001C36061C06361C0000", "0000060E16363F06060000", "00003E303C3606263C0000", "00001C36303C36361C0000", "00003E36060C0C18180000",
            "00001C36361C36361C0000", "00001C36361E06361C0000", "0000000000180000180000", "0000000000180000181020", "0000000C1830180C000000", "000000003C003C00000000",
            "000000180C060C18000000", "0000001C260C1800180000", "00001C32262A2A27301C00", "0000003C1C143E36370000", "0000003C363C36363C0000", "0000001E363030361C0000",
            "0000003C363636363C0000", "0000003E303C30363E0000", "0000003E303C3030380000", "0000001C36303E361E0000", "00000037363E3636370000", "0000003C181818183C0000",
            "0000001E0C0C2C2C380000", "0000003634383C363B0000", "00000038303030363E0000", "0000002236363E2A2A0000", "000000373A3A3636320000", "0000001C363636361C0000",
            "0000003C36363C30380000", "0000001C363636361C0600", "0000003C36363C363B0000", "0000001E323C0E263C0000", "0000003E1A1818183C0000", "00000037363636361C0000",
            "0000003736141C1C080000", "0000002B2A2A3E1C140000", "000000331E0C0C1E330000", "00000033331E0C0C1E0000", "0000003E360C18363E0000", "00001C1818181818181C00",
            "0000202010100808040400", "00001C0C0C0C0C0C0C1C00", "0000081C36000000000000", "000000000000000000003F", "0000180804000000000000", "000000001C361E363F0000",
            "000030303C3636363C0000", "000000001C3630361C0000", "00000E061E3636361F0000", "000000001C363E301E0000", "00000E183E1818183E0000", "000000001B3636361E063C",
            "000030303C363636360000", "00000C003C0C0C0C3F0000", "00000C003C0C0C0C0C0C38", "00003030363C383C370000", "00003C0C0C0C0C0C3F0000", "000000003C3E2A2A2A0000",
            "000000002C363636360000", "000000001C3636361C0000", "000000003C3636363C3038", "000000001B3636361E060F", "00000000371D18183C0000", "000000001E381E073E0000",
            "000018183E18181B0E0000", "00000000363636361F0000", "0000000036361C1C080000", "000000002B2A3E1E140000", "000000003B1E0C1E370000", "00000000373636141C1830",
            "000000003E2C18363E0000", "0000060C0C180C0C0C0600", "0000000808080808080800", "00003018180C1818183000", "000000001A2C0000000000"
    };

    private static final int[][] FONT = buildFont();

    private static int[][] buildFont() {
        int[][] f = new int[GLYPH_HEX.length][11];
        for (int i = 0; i < GLYPH_HEX.length; i++) {
            for (int r = 0; r < 11; r++) {
                f[i][r] = Integer.parseInt(GLYPH_HEX[i].substring(r * 2, r * 2 + 2), 16);
            }
        }
        return f;
    }

    private static final String[] HEART = {
            ".XX.XX.",
            "XXXXXXX",
            "XXXXXXX",
            "XXXXXXX",
            ".XXXXX.",
            "..XXX..",
            "...X..."
    };

    static int textWidth(String s) {
        return s.length() * 6;
    }

    static void text(Graphics2D g, String s, int x, int y, Color c) {
        g.setColor(c);
        int px = x;
        for (int i = 0; i < s.length(); i++) {
            int idx = s.charAt(i) - 32;
            if (idx < 0 || idx >= FONT.length) {
                idx = '?' - 32;
            }
            int[] rows = FONT[idx];
            for (int r = 0; r < 11; r++) {
                int v = rows[r];
                if (v == 0) {
                    continue;
                }
                for (int col = 0; col < 6; col++) {
                    if (((v >> (5 - col)) & 1) != 0) {
                        g.fillRect(px + col, y + r, 1, 1);
                    }
                }
            }
            px += 6;
        }
    }

    /** state: 0 = trống, 1 = nửa tim, 2 = đầy. (x, y) là góc trên trái của phần tô màu 7x7. */
    static void heart(Graphics2D g, int x, int y, int state) {
        g.setColor(Color.BLACK);
        for (int r = 0; r < HEART.length; r++) {
            for (int c = 0; c < 7; c++) {
                if (HEART[r].charAt(c) == 'X') {
                    g.fillRect(x + c - 1, y + r - 1, 3, 3);
                }
            }
        }
        Color red = new Color(0xE8, 0x1E, 0x1E);
        Color gray = new Color(0x55, 0x33, 0x33);
        Color shine = new Color(0xFF, 0xB0, 0xB0);
        for (int r = 0; r < HEART.length; r++) {
            for (int c = 0; c < 7; c++) {
                if (HEART[r].charAt(c) != 'X') {
                    continue;
                }
                boolean filled = state == 2 || (state == 1 && c <= 3);
                Color col = filled ? red : gray;
                if (filled && r == 1 && c == 1) {
                    col = shine;
                }
                g.setColor(col);
                g.fillRect(x + c, y + r, 1, 1);
            }
        }
    }

    /**
     * Vẽ 1 người chơi tại tâm (cx, cy) trên ảnh rộng canvasW x canvasH.
     * head có thể null, khi đó vẽ ô màu kèm chữ cái đầu tên.
     */
    static void drawPlayer(Graphics2D g, int canvasW, int canvasH, SquareShot.PlayerInfo p,
                           int cx, int cy, BufferedImage head, boolean showHealth) {
        // mũi tên chỉ hướng nhìn (yaw 0 = nam/+z, 90 = tây/-x)
        double yaw = Math.toRadians(p.yaw());
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        double px = -dz;
        double pz = dx;
        Polygon arrow = new Polygon();
        arrow.addPoint((int) Math.round(cx + dx * 25), (int) Math.round(cy + dz * 25));
        arrow.addPoint((int) Math.round(cx + dx * 13 + px * 6), (int) Math.round(cy + dz * 13 + pz * 6));
        arrow.addPoint((int) Math.round(cx + dx * 13 - px * 6), (int) Math.round(cy + dz * 13 - pz * 6));
        Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 200));
        g.drawPolygon(arrow);
        g.setColor(Color.WHITE);
        g.fillPolygon(arrow);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAa);

        // đầu người chơi
        int hs = 18;
        int hx = cx - hs / 2;
        int hy = cy - hs / 2;
        g.setColor(Color.BLACK);
        g.fillRect(hx - 1, hy - 1, hs + 2, hs + 2);
        if (head != null) {
            Object oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(head, hx, hy, hs, hs, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    oldInterp == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : oldInterp);
        } else {
            float hue = (Math.abs(p.name().hashCode()) % 360) / 360f;
            g.setColor(Color.getHSBColor(hue, 0.45f, 0.75f));
            g.fillRect(hx, hy, hs, hs);
            String ini = p.name().isEmpty() ? "?" : p.name().substring(0, 1).toUpperCase();
            text(g, ini, cx - 3, cy - 5, Color.WHITE);
        }

        // bảng tên + thanh máu
        int heartsW = showHealth ? 10 * 8 + 1 : 0;
        int contentW = Math.max(textWidth(p.name()), heartsW);
        int boxW = contentW + 12;
        int boxH = 11 + (showHealth ? 4 + 9 : 0) + 9;
        boolean plateLeft = dx > 0.35; // tránh đè lên mũi tên khi đang nhìn sang phải
        int bx = plateLeft ? cx - 13 - boxW : cx + 13;
        if (bx + boxW > canvasW - 2) {
            bx = cx - 13 - boxW;
        }
        bx = Math.max(2, Math.min(bx, canvasW - boxW - 2));
        int by = Math.max(2, Math.min(cy - 12, canvasH - boxH - 2));

        g.setColor(new Color(0, 0, 0, 175));
        g.fillRoundRect(bx, by, boxW, boxH, 8, 8);
        text(g, p.name(), bx + 7, by + 5, new Color(0, 0, 0, 200));
        text(g, p.name(), bx + 6, by + 4, Color.WHITE);
        if (showHealth) {
            double hp = Math.max(0, Math.min(20, p.health()));
            for (int i = 0; i < 10; i++) {
                int state = hp >= 2 * (i + 1) ? 2 : (hp >= 2 * i + 1 ? 1 : 0);
                heart(g, bx + 7 + i * 8, by + 4 + 11 + 5, state);
            }
        }
    }
}
