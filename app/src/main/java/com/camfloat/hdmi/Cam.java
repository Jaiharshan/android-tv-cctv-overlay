package com.camfloat.hdmi;

import java.util.ArrayList;
import java.util.List;

/** One camera: a label and a stream address (rtsp://... or hdmi://PORT). Stored on the TV only. */
final class Cam {
    final String name;
    final String url;

    Cam(String name, String url) {
        this.name = name == null || name.isEmpty() ? "Camera" : name.replace("|", "/").replace("\n", " ");
        this.url = url.trim();
    }

    boolean isHdmi() {
        return url.startsWith("hdmi://");
    }

    int hdmiPort() {
        try {
            return Integer.parseInt(url.substring("hdmi://".length()).trim());
        } catch (Exception e) {
            return 1;
        }
    }

    /** Storage format: one camera per line, "name|url". */
    static List<Cam> parse(String s, String lineSep) {
        List<Cam> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split(lineSep)) {
            int i = line.indexOf('|');
            if (i <= 0 || i == line.length() - 1) continue;
            out.add(new Cam(line.substring(0, i), line.substring(i + 1)));
        }
        return out;
    }

    static String serialize(List<Cam> list) {
        StringBuilder b = new StringBuilder();
        for (Cam c : list) {
            if (b.length() > 0) b.append('\n');
            b.append(c.name).append('|').append(c.url);
        }
        return b.toString();
    }
}
