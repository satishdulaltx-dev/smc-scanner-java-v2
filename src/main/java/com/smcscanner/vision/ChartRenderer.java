package com.smcscanner.vision;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders a candlestick chart with volume, VWAP, and signal levels as a PNG byte array.
 * Uses Java 2D only — no external charting library needed.
 */
public class ChartRenderer {

    private static final int W = 900, H = 520;
    private static final int PAD_L = 8, PAD_R = 64, PAD_T = 36, PAD_B = 36;
    private static final int VOL_HEIGHT = 100; // pixels for volume panel
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm").withZone(ET);

    private static final Color BG          = new Color(0x0d1117);
    private static final Color GRID        = new Color(0x21262d);
    private static final Color BULL        = new Color(0x26a69a);
    private static final Color BEAR        = new Color(0xef5350);
    private static final Color VWAP_COLOR  = new Color(0xffa726);
    private static final Color ENTRY_COLOR = new Color(0x42a5f5);
    private static final Color SL_COLOR    = new Color(0xef5350);
    private static final Color TP_COLOR    = new Color(0x66bb6a);
    private static final Color TEXT_COLOR  = new Color(0xc9d1d9);
    private static final Color SUBTEXT     = new Color(0x8b949e);

    private ChartRenderer() {}

    public static byte[] render(List<OHLCV> allBars, TradeSetup signal) {
        return renderInternal(allBars, signal, signal != null ? signal.getTicker() : "CHART");
    }

    public static byte[] renderBasic(List<OHLCV> allBars, String ticker) {
        return renderInternal(allBars, null, ticker);
    }

    private static byte[] renderInternal(List<OHLCV> allBars, TradeSetup signal, String tickerLabel) {
        // Use last 80 bars max
        int n = Math.min(80, allBars.size());
        List<OHLCV> bars = allBars.subList(allBars.size() - n, allBars.size());

        double[] vwap = calcVwap(bars);

        double priceMin = Double.MAX_VALUE, priceMax = Double.MIN_VALUE;
        for (OHLCV b : bars) {
            priceMin = Math.min(priceMin, b.getLow());
            priceMax = Math.max(priceMax, b.getHigh());
        }
        // Include signal levels in price range
        if (signal != null) {
            priceMin = Math.min(priceMin, signal.getStopLoss() * 0.999);
            priceMax = Math.max(priceMax, signal.getTakeProfit() * 1.001);
        }
        double pricePad = (priceMax - priceMin) * 0.05;
        priceMin -= pricePad;
        priceMax += pricePad;

        double volMax = bars.stream().mapToDouble(OHLCV::getVolume).max().orElse(1);

        int candleW = Math.max(2, (W - PAD_L - PAD_R) / n - 1);
        int chartTop = PAD_T;
        int chartBot = H - PAD_B - VOL_HEIGHT - 8;
        int chartH   = chartBot - chartTop;
        int volTop   = chartBot + 8;
        int volBot   = H - PAD_B;

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        // Grid lines (5 horizontal)
        g.setColor(GRID);
        g.setStroke(new BasicStroke(0.5f));
        for (int i = 0; i <= 4; i++) {
            int y = chartTop + chartH * i / 4;
            g.drawLine(PAD_L, y, W - PAD_R, y);
            double price = priceMax - (priceMax - priceMin) * i / 4;
            g.setColor(SUBTEXT);
            g.setFont(new Font("Monospaced", Font.PLAIN, 10));
            g.drawString(String.format("%.2f", price), W - PAD_R + 3, y + 4);
            g.setColor(GRID);
        }

        // Candles + volume
        for (int i = 0; i < n; i++) {
            OHLCV b = bars.get(i);
            int x = PAD_L + i * (candleW + 1) + candleW / 2;

            boolean bull = b.getClose() >= b.getOpen();
            Color col = bull ? BULL : BEAR;
            g.setColor(col);

            // Wick
            int wickTop = priceToY(b.getHigh(), priceMin, priceMax, chartTop, chartH);
            int wickBot = priceToY(b.getLow(),  priceMin, priceMax, chartTop, chartH);
            g.setStroke(new BasicStroke(1f));
            g.drawLine(x, wickTop, x, wickBot);

            // Body
            int bodyTop = priceToY(Math.max(b.getOpen(), b.getClose()), priceMin, priceMax, chartTop, chartH);
            int bodyBot = priceToY(Math.min(b.getOpen(), b.getClose()), priceMin, priceMax, chartTop, chartH);
            int bodyH = Math.max(1, bodyBot - bodyTop);
            g.fillRect(x - candleW / 2, bodyTop, candleW, bodyH);

            // Volume bar
            int volH = (int) ((b.getVolume() / volMax) * (volBot - volTop));
            Color volColor = new Color(col.getRed(), col.getGreen(), col.getBlue(), 160);
            g.setColor(volColor);
            g.fillRect(x - candleW / 2, volBot - volH, candleW, volH);
        }

        // VWAP line
        g.setColor(VWAP_COLOR);
        g.setStroke(new BasicStroke(1.5f));
        for (int i = 1; i < n; i++) {
            if (vwap[i] <= 0 || vwap[i-1] <= 0) continue;
            int x1 = PAD_L + (i-1) * (candleW + 1) + candleW / 2;
            int x2 = PAD_L + i * (candleW + 1) + candleW / 2;
            int y1 = priceToY(vwap[i-1], priceMin, priceMax, chartTop, chartH);
            int y2 = priceToY(vwap[i],   priceMin, priceMax, chartTop, chartH);
            g.draw(new Line2D.Float(x1, y1, x2, y2));
        }

        // Signal levels (entry, SL, TP)
        if (signal != null) {
            drawHLine(g, signal.getEntry(),     ENTRY_COLOR, "Entry " + String.format("%.2f", signal.getEntry()),     priceMin, priceMax, chartTop, chartH, true);
            drawHLine(g, signal.getStopLoss(),  SL_COLOR,   "SL "    + String.format("%.2f", signal.getStopLoss()),  priceMin, priceMax, chartTop, chartH, false);
            drawHLine(g, signal.getTakeProfit(),TP_COLOR,   "TP "    + String.format("%.2f", signal.getTakeProfit()), priceMin, priceMax, chartTop, chartH, false);
        }

        // Title bar
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        if (signal != null) {
            String dir   = signal.getDirection().toUpperCase();
            Color titleColor = "long".equalsIgnoreCase(dir) ? BULL : BEAR;
            g.setColor(titleColor);
            g.drawString(signal.getTicker() + "  " + dir + "  conf=" + signal.getConfidence() + "  " + signal.getVolatility().toUpperCase(), PAD_L + 4, 22);
        } else {
            g.setColor(TEXT_COLOR);
            g.drawString(tickerLabel + "  [VISION SCAN]", PAD_L + 4, 22);
        }

        // VWAP label
        g.setColor(VWAP_COLOR);
        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g.drawString("VWAP", W - PAD_R - 36, 22);

        // Time labels at bottom
        g.setColor(SUBTEXT);
        g.setFont(new Font("Monospaced", Font.PLAIN, 9));
        for (int i = 0; i < n; i += Math.max(1, n / 8)) {
            OHLCV b = bars.get(i);
            int x = PAD_L + i * (candleW + 1);
            String t = TIME_FMT.format(Instant.ofEpochMilli(b.getTimestamp()));
            g.drawString(t, x, H - 4);
        }

        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Chart render failed", e);
        }
    }

    private static void drawHLine(Graphics2D g, double price, Color color, String label,
                                   double priceMin, double priceMax, int chartTop, int chartH, boolean solid) {
        if (price <= 0) return;
        int y = priceToY(price, priceMin, priceMax, chartTop, chartH);
        g.setColor(color);
        float[] dash = solid ? null : new float[]{4f, 4f};
        g.setStroke(dash != null
            ? new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
            : new BasicStroke(1.5f));
        g.drawLine(PAD_L, y, W - PAD_R, y);
        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g.drawString(label, W - PAD_R + 3, y - 2);
    }

    private static int priceToY(double price, double min, double max, int top, int h) {
        double ratio = (max - price) / (max - min);
        return top + (int) (ratio * h);
    }

    private static double[] calcVwap(List<OHLCV> bars) {
        double[] vwap = new double[bars.size()];
        // Find session start (first bar at or after 9:30 ET)
        double cumTPV = 0, cumVol = 0;
        boolean sessionStarted = false;
        for (int i = 0; i < bars.size(); i++) {
            OHLCV b = bars.get(i);
            int hour = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET).getHour();
            int min  = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET).getMinute();
            boolean rth = (hour == 9 && min >= 30) || (hour > 9 && hour < 16);
            if (rth) {
                if (!sessionStarted) { cumTPV = 0; cumVol = 0; sessionStarted = true; }
                double tp = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
                cumTPV += tp * b.getVolume();
                cumVol += b.getVolume();
                vwap[i] = cumVol > 0 ? cumTPV / cumVol : 0;
            }
        }
        return vwap;
    }
}
