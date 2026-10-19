package io.github.lollip0529.biscuits;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// toolscreen's mode sizes (thin, eyezoom etc) from its config.toml, worked out the same way toolscreen does
public final class Toolscreen {
    public static final class Mode {
        public final String id;
        public final int width, height;

        Mode(String id, int width, int height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }
    }

    public final List<Mode> modes;
    public final String current;

    private Toolscreen(List<Mode> modes, String current) {
        this.modes = modes;
        this.current = current;
    }

    public static Toolscreen read() {
        // same lookup order as toolscreen's GetToolscreenPath
        File dir = new File("toolscreen");
        if (!dir.isDirectory()) dir = new File(System.getProperty("user.home"), ".config/toolscreen");
        File config = new File(dir, "config.toml");
        if (!config.isFile()) return null;

        GLFWVidMode screen = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        int sw = screen.width(), sh = screen.height();
        List<Mode> modes = new ArrayList<>();
        try {
            Map<String, String> block = null;
            for (String raw : Files.readAllLines(config.toPath(), StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.startsWith("[")) {
                    if (block != null) add(modes, block, sw, sh);
                    block = line.equals("[[mode]]") ? new HashMap<>() : null;
                } else if (block != null && line.contains("=")) {
                    int eq = line.indexOf('=');
                    block.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            if (block != null) add(modes, block, sw, sh);
        } catch (IOException e) {
            return null;
        }

        String current = null;
        try {
            current = new String(Files.readAllBytes(new File(dir, "mode.txt").toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
        }
        return new Toolscreen(modes, current);
    }

    private static void add(List<Mode> modes, Map<String, String> block, int sw, int sh) {
        String id = unquote(block.get("id"));
        if (id == null) return;
        boolean relative = "true".equals(block.get("useRelativeSize"));
        try {
            int w = size(block.get("width"), block.get("relativeWidth"), relative, sw, sh, sw);
            int h = size(block.get("height"), block.get("relativeHeight"), relative, sw, sh, sh);
            if (w > 0 && h > 0) modes.add(new Mode(id, w, h));
        } catch (RuntimeException ignored) {
            // bad expression. toolscreen falls back to defaults there, without a size there's nothing to show
        }
    }

    // config_toml.cpp: a string is an expression, 0..1 is a fraction of the screen, anything else is pixels
    private static int size(String value, String relativeKey, boolean useRelative, int sw, int sh, int screen) {
        if (value == null) return -1;
        if (value.startsWith("'") || value.startsWith("\"")) {
            return (int) Math.floor(new Expr(unquote(value), sw, sh).parse());
        }
        double v = Double.parseDouble(value);
        double rel = relativeKey != null ? Double.parseDouble(relativeKey) : v >= 0 && v <= 1 ? v : -1;
        if (rel >= 0 && rel <= 1 && (useRelative || v <= 1)) return Math.max(1, Math.round((float) rel * screen));
        return (int) v;
    }

    private static String unquote(String s) {
        if (s == null || s.length() < 2) return s;
        char c = s.charAt(0);
        return (c == '\'' || c == '"') && s.charAt(s.length() - 1) == c ? s.substring(1, s.length() - 1) : s;
    }

    // small version of toolscreen's expression_parser.cpp. + - * / ( ), screenWidth/screenHeight, min max floor ceil
    // round abs roundEven
    private static final class Expr {
        private final String s;
        private final int sw, sh;
        private int i;

        Expr(String s, int sw, int sh) {
            this.s = s;
            this.sw = sw;
            this.sh = sh;
        }

        double parse() {
            double v = this.sum();
            this.skip();
            if (this.i != this.s.length()) throw new IllegalArgumentException("trailing input in " + this.s);
            return v;
        }

        private double sum() {
            double v = this.product();
            while (true) {
                if (this.eat('+')) v += this.product();
                else if (this.eat('-')) v -= this.product();
                else return v;
            }
        }

        private double product() {
            double v = this.unary();
            while (true) {
                if (this.eat('*')) v *= this.unary();
                else if (this.eat('/')) v /= this.unary();
                else return v;
            }
        }

        private double unary() {
            if (this.eat('-')) return -this.unary();
            if (this.eat('(')) {
                double v = this.sum();
                this.expect(')');
                return v;
            }
            this.skip();
            int start = this.i;
            if (this.i < this.s.length() && (Character.isDigit(this.s.charAt(this.i)) || this.s.charAt(this.i) == '.')) {
                while (this.i < this.s.length() && (Character.isDigit(this.s.charAt(this.i)) || this.s.charAt(this.i) == '.')) this.i++;
                return Double.parseDouble(this.s.substring(start, this.i));
            }
            while (this.i < this.s.length() && Character.isLetter(this.s.charAt(this.i))) this.i++;
            String name = this.s.substring(start, this.i);
            if (name.equals("screenWidth")) return this.sw;
            if (name.equals("screenHeight")) return this.sh;

            this.expect('(');
            List<Double> args = new ArrayList<>();
            if (!this.eat(')')) {
                do args.add(this.sum()); while (this.eat(','));
                this.expect(')');
            }
            switch (name) {
                case "min": return Math.min(args.get(0), args.get(1));
                case "max": return Math.max(args.get(0), args.get(1));
                case "floor": return Math.floor(args.get(0));
                case "ceil": return Math.ceil(args.get(0));
                case "round": return Math.round(args.get(0));
                case "abs": return Math.abs(args.get(0));
                // up to the next even number
                case "roundEven": return Math.ceil(args.get(0) / 2) * 2;
                default: throw new IllegalArgumentException("unknown function " + name);
            }
        }

        private boolean eat(char c) {
            this.skip();
            if (this.i < this.s.length() && this.s.charAt(this.i) == c) {
                this.i++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!this.eat(c)) throw new IllegalArgumentException("expected " + c + " in " + this.s);
        }

        private void skip() {
            while (this.i < this.s.length() && this.s.charAt(this.i) == ' ') this.i++;
        }
    }
}
