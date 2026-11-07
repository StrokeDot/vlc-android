/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.util.LinkedHashMap;
import java.util.Map;

/** Saved caption choices, bounded before sending to a Cast receiver. */
public final class CastSubtitleStyle {
    public final int family, color, edge, background;
    public final double scale;
    public final boolean bold, italic;
    public CastSubtitleStyle() { this(0, 1.0, 0, 1, 0, false, false); }
    public CastSubtitleStyle(int family, double scale, int color, int edge, int background, boolean bold, boolean italic) {
        this.family = family >= 0 && family <= 2 ? family : 0;
        this.scale = !Double.isNaN(scale) && !Double.isInfinite(scale) ? Math.max(0.75, Math.min(2.0, scale)) : 1.0;
        this.color = color >= 0 && color <= 2 ? color : 0;
        this.edge = edge >= 0 && edge <= 2 ? edge : 1;
        this.background = background == 1 ? 1 : 0;
        this.bold = bold; this.italic = italic;
    }
    public Map<String, Object> castStyle() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fontGenericFamily", new String[]{"SANS_SERIF", "SERIF", "MONOSPACED_SANS_SERIF"}[family]);
        values.put("fontScale", scale);
        values.put("fontStyle", bold ? (italic ? "BOLD_ITALIC" : "BOLD") : (italic ? "ITALIC" : "NORMAL"));
        values.put("foregroundColor", new String[]{"#FFFFFFFF", "#FFFF00FF", "#00FFFFFF"}[color]);
        values.put("backgroundColor", background == 1 ? "#000000A0" : "#00000000");
        values.put("edgeType", new String[]{"NONE", "OUTLINE", "DROP_SHADOW"}[edge]);
        values.put("edgeColor", "#000000FF");
        return values;
    }
}
